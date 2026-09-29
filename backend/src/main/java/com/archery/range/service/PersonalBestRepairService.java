package com.archery.range.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.domain.Round;
import com.archery.range.dto.RoundDtos;
import com.archery.range.repository.RoundRepository;

/**
 * 个人最好成绩历史修复（可重复执行）。
 *
 * 扫描全部回合，按 {@link PersonalBestSupport} 的口径判定资格并划分可比组别，
 * 然后按完成时间重算每个组别的唯一最好回合，只改 rounds.personal_best 标记：
 * 不动箭支顺序、环值、总分、平均环、认证证据（cert_eval_round 外键）与原始完成时间。
 *
 * 判定完全基于数据本身（总分 → 完成时间 → 回合 id 的全序），
 * 第一次执行把旧口径标记修正到位，之后再执行 marksChanged 恒为 0，结果幂等。
 *
 * 无法确定射距、箭数不完整、弓种无法识别、关联会员 / 箭道缺失或非已完成的回合
 * 保留原始成绩、清除其可能误带的最好标记，并在报告的 excluded 中逐条列出，绝不静默归组。
 */
@Service
public class PersonalBestRepairService {

    private final RoundRepository roundRepository;
    private final RoundService roundService;

    public PersonalBestRepairService(RoundRepository roundRepository, RoundService roundService) {
        this.roundRepository = roundRepository;
        this.roundService = roundService;
    }

    /** 会员详情：按可比组别列出该会员当前每条个人最好（每组至多一条） */
    @Transactional(readOnly = true)
    public RoundDtos.MemberBestsView memberBests(Long memberId) {
        List<Round> rounds = roundRepository.findByMemberIdOrderByStartTimeDesc(memberId);
        Map<PersonalBestSupport.GroupKey, List<Round>> groups = partition(rounds).groups();
        String memberName = rounds.stream()
                .map(Round::getMember)
                .filter(java.util.Objects::nonNull)
                .map(com.archery.range.domain.Member::getName)
                .findFirst()
                .orElse("");

        List<RoundDtos.BestGroupView> views = new ArrayList<>();
        for (Map.Entry<PersonalBestSupport.GroupKey, List<Round>> entry : groups.entrySet()) {
            List<Round> sameGroup = entry.getValue();
            Round winner = PersonalBestSupport.winnerOf(sameGroup);
            if (winner != null) {
                views.add(new RoundDtos.BestGroupView(toGroupView(entry.getKey()),
                        sameGroup.size(), roundService.toView(winner)));
            }
        }
        views.sort(Comparator.comparing(v -> v.group().label()));
        return new RoundDtos.MemberBestsView(memberId, memberName, views);
    }

    /** 预演：完整重算但不落库，返回修复前后的差异供确认 */
    @Transactional(readOnly = true)
    public RoundDtos.RepairReport preview() {
        return repair(false);
    }

    /** 执行历史修复并落库；可重复执行，多次执行结果一致 */
    @Transactional
    public RoundDtos.RepairReport repair() {
        return repair(true);
    }

    private RoundDtos.RepairReport repair(boolean apply) {
        List<Round> all = roundRepository.findAll();

        // 不能参与比较的回合：保留原始成绩，只清除可能被旧口径误置的最好标记，并逐条登记原因
        List<RoundDtos.ExcludedRoundView> excluded = new ArrayList<>();
        List<Round> eligible = new ArrayList<>();
        for (Round round : all) {
            PersonalBestSupport.Eligibility check = PersonalBestSupport.check(round);
            if (check.eligible()) {
                eligible.add(round);
            } else {
                excluded.add(new RoundDtos.ExcludedRoundView(
                        round.getId(),
                        round.getRoundNo(),
                        round.getMember() == null ? null : round.getMember().getId(),
                        round.getMember() == null ? "" : round.getMember().getName(),
                        round.getStatus(),
                        com.archery.range.domain.RangeDict.roundStatusName(round.getStatus()),
                        check.reasonCode(),
                        check.reason()));
            }
        }

        Map<PersonalBestSupport.GroupKey, List<Round>> groups = partition(eligible).groups();

        int marksChanged = 0;
        List<Long> ineligibleWithMark = all.stream()
                .filter(round -> !PersonalBestSupport.check(round).eligible())
                .filter(round -> Boolean.TRUE.equals(round.getPersonalBest()))
                .map(Round::getId)
                .toList();
        marksChanged += ineligibleWithMark.size();
        if (apply && !ineligibleWithMark.isEmpty()) {
            // 此刻还没有对托管实体做任何改动，先批量清掉异常回合上旧口径误置的标记
            // （只更新标记列；不走实体保存，避免把缺失的关联对象覆盖成 NULL）
            roundRepository.clearPersonalBest(ineligibleWithMark);
        }

        // 每个可比组别按完成时间重算唯一最好；异常实体保持内存原样、绝不参与保存
        List<Round> touched = new ArrayList<>();
        for (List<Round> sameGroup : groups.values()) {
            marksChanged += PersonalBestSupport.applyBestMarks(sameGroup);
            if (apply) {
                touched.addAll(sameGroup);
            }
        }

        if (!touched.isEmpty()) {
            roundRepository.saveAll(touched);
        }

        List<RoundDtos.BestGroupView> bests = new ArrayList<>();
        for (Map.Entry<PersonalBestSupport.GroupKey, List<Round>> entry : groups.entrySet()) {
            Round winner = PersonalBestSupport.winnerOf(entry.getValue());
            if (winner != null) {
                bests.add(new RoundDtos.BestGroupView(toGroupView(entry.getKey()),
                        entry.getValue().size(), roundService.toView(winner)));
            }
        }
        bests.sort(Comparator.comparing((RoundDtos.BestGroupView v) -> v.group().memberId())
                .thenComparing(v -> v.group().label()));
        excluded.sort(Comparator.comparing(RoundDtos.ExcludedRoundView::id));

        return new RoundDtos.RepairReport(
                LocalDateTime.now(),
                apply,
                all.size(),
                eligible.size(),
                excluded.size(),
                groups.size(),
                marksChanged,
                bests,
                excluded);
    }

    /** 按可比组别分桶：组键含会员，不同会员 / 弓种 / 射距 / 箭数天然分开 */
    private Partition partition(List<Round> rounds) {
        Map<PersonalBestSupport.GroupKey, List<Round>> groups = new LinkedHashMap<>();
        for (Round round : rounds) {
            PersonalBestSupport.Eligibility check = PersonalBestSupport.check(round);
            if (!check.eligible()) {
                continue;
            }
            groups.computeIfAbsent(check.key(), key -> new ArrayList<>()).add(round);
        }
        // TreeMap 让报告分组顺序稳定（会员 → 弓种 → 射距 → 箭数）
        Map<PersonalBestSupport.GroupKey, List<Round>> ordered = new TreeMap<>(
                Comparator.comparing(PersonalBestSupport.GroupKey::memberId)
                        .thenComparing(PersonalBestSupport.GroupKey::bowType)
                        .thenComparing(PersonalBestSupport.GroupKey::distance)
                        .thenComparing(PersonalBestSupport.GroupKey::arrowCount));
        ordered.putAll(groups);
        return new Partition(ordered);
    }

    private record Partition(Map<PersonalBestSupport.GroupKey, List<Round>> groups) {
    }

    private RoundDtos.ComparableGroupView toGroupView(PersonalBestSupport.GroupKey key) {
        return new RoundDtos.ComparableGroupView(
                key.memberId(),
                key.bowType(),
                com.archery.range.domain.RangeDict.bowTypeName(key.bowType()),
                key.distance(),
                key.arrowCount(),
                key.label());
    }
}
