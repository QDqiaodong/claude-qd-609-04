package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.ArrowScore;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.dto.RoundDtos;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;
/**
 * 计分回合。
 *
 * 本项目的核心数据访问路线在这里落地：Round 持有 {@code List<ArrowScore>}，
 * 用 {@code @OneToMany + @JoinTable + @OrderColumn(name = "shot_index")} 映射成有序集合，
 * 于是「第几支箭」= 列表下标。记一支箭就是往列表尾部 append 后 save，
 * 顺序由 Hibernate 维护的 shot_index 保证，不会乱。
 *
 * 个人最好成绩按可比组别（会员 + 弓种 + 射距 + 规定箭数）独立计算，
 * 判定口径统一收在 {@link PersonalBestSupport}，提交与历史修复共用同一套规则。
 */
@Service
public class RoundService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RoundRepository roundRepository;
    private final MemberRepository memberRepository;
    private final LaneRepository laneRepository;

    public RoundService(RoundRepository roundRepository, MemberRepository memberRepository, LaneRepository laneRepository) {
        this.roundRepository = roundRepository;
        this.memberRepository = memberRepository;
        this.laneRepository = laneRepository;
    }

    @Transactional(readOnly = true)
    public List<RoundDtos.RoundView> list() {
        return roundRepository.findAllByOrderByStartTimeDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<RoundDtos.RoundView> listByMember(Long memberId) {
        return roundRepository.findByMemberIdOrderByStartTimeDesc(memberId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<RoundDtos.RoundView> listByStatus(String status) {
        return roundRepository.findByStatusOrderByStartTimeDesc(status).stream().map(this::toView).toList();
    }

    /** 成绩单：回合 + 按 shot_index 顺序排好的每一支箭 */
    @Transactional(readOnly = true)
    public RoundDtos.RoundDetailView detail(Long id) {
        return toDetail(require(id));
    }

    @Transactional
    public RoundDtos.RoundDetailView start(RoundDtos.RoundStartReq req) {
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(() -> new BizException("会员不存在：id=" + req.memberId()));
        // 已合并封存的来源档案不能再开始新计分回合
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException("会员卡 [" + member.getCardNo() + "] 已合并封存，不能再开始新回合"
                    + (member.getMergedIntoId() == null ? ""
                            : "，请使用保留档案 id=" + member.getMergedIntoId()));
        }
        Lane lane = laneRepository.findByIdForUpdate(req.laneId())
                .orElseThrow(() -> new BizException("箭道不存在：id=" + req.laneId()));
        if ("LOCKED".equals(lane.getStatus())) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 处于安全锁定（停射事件未放行），不能开打");
        }
        if ("MAINTENANCE".equals(lane.getStatus())) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 维护中，不能开打");
        }
        if (!RangeDict.isValidGroupSize(req.arrowCount())) {
            throw new BizException("一组只能是 6 支或 12 支箭");
        }
        String bowType = req.bowType() == null || req.bowType().isBlank()
                ? PersonalBestSupport.DEFAULT_BOW_TYPE
                : req.bowType().trim().toUpperCase();
        if (!RangeDict.isValidBowType(bowType)) {
            throw new BizException("弓种只能是 反曲弓 / 复合弓 / 传统弓");
        }

        Round round = new Round();
        round.setRoundNo(nextRoundNo());
        round.setMember(member);
        round.setLane(lane);
        round.setBowType(bowType);
        round.setStartTime(LocalDateTime.now());
        round.setCompletedAt(null);
        round.setArrowCount(req.arrowCount());
        round.setTotalScore(0);
        round.setAverageScore(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        round.setPersonalBest(false);
        round.setStatus("ONGOING");
        round.setArrows(new ArrayList<>());

        // 开打即占用该箭道
        if ("OPEN".equals(lane.getStatus())) {
            lane.setStatus("OCCUPIED");
            lane.setOccupantId(member.getId());
            lane.setOccupantName(member.getName());
            lane.setOpenedAt(round.getStartTime());
            lane.setPlannedHours(1);
            laneRepository.save(lane);
        }
        return toDetail(roundRepository.save(round));
    }

    /**
     * 记一支箭：append 到有序集合尾部，再重算总分与平均环。
     * 暂停态（安全联锁）禁止记箭，已记录箭支的顺序与分数原样保留。
     */
    @Transactional
    public RoundDtos.RoundDetailView shoot(Long id, RoundDtos.ShotReq req) {
        Round round = requireForUpdate(id);
        rejectIfPaused(round, "记箭");
        if ("SUBMITTED".equals(round.getStatus())) {
            throw new BizException("回合 [" + round.getRoundNo() + "] 已提交，不能再记箭");
        }
        if (round.getArrows().size() >= round.getArrowCount()) {
            throw new BizException("本组 " + round.getArrowCount() + " 支箭已打满，请先提交回合");
        }
        String ring = req.ring() == null ? "" : req.ring().trim().toUpperCase();
        if (!RangeDict.isValidRing(ring)) {
            throw new BizException("环数只能是 X / 10 / 9 / 8 / 7 / 6 / 5 / 4 / 3 / 2 / 1 / M");
        }
        round.getArrows().add(new ArrowScore(ring, RangeDict.ringValue(ring)));
        recalculate(round);
        return toDetail(roundRepository.save(round));
    }

    /** 撤销最后一支箭（打错环数时回滚一支）。暂停态禁止撤销。 */
    @Transactional
    public RoundDtos.RoundDetailView undo(Long id) {
        Round round = requireForUpdate(id);
        rejectIfPaused(round, "撤销");
        if ("SUBMITTED".equals(round.getStatus())) {
            throw new BizException("回合已提交，不能撤销");
        }
        if (round.getArrows().isEmpty()) {
            throw new BizException("本回合还没有记箭，无需撤销");
        }
        round.getArrows().remove(round.getArrows().size() - 1);
        recalculate(round);
        return toDetail(roundRepository.save(round));
    }

    /**
     * 提交回合：必须打满一组，提交时只与同一可比组别（会员 + 弓种 + 射距 + 箭数）的历史回合比较：
     * 分数更高 → 本回合成为该组唯一个人最好并清除旧标记；同分 → 最早完成的回合保持最好。
     * 组内判定走与历史修复完全相同的规则（{@link PersonalBestSupport}），重复提交 / 重算不会来回切换。
     * 暂停态禁止提交。
     */
    @Transactional
    public RoundDtos.RoundDetailView submit(Long id) {
        Round round = requireForUpdate(id);
        rejectIfPaused(round, "提交");
        if ("SUBMITTED".equals(round.getStatus())) {
            throw new BizException("回合已提交");
        }
        if (round.getArrows().size() < round.getArrowCount()) {
            throw new BizException("还差 " + (round.getArrowCount() - round.getArrows().size()) + " 支箭未打完，不能提交");
        }

        // 先落完成状态与完成时间；进行中 / 暂停回合永远没有完成时间，不能参与比较
        LocalDateTime completedAt = LocalDateTime.now();
        round.setCompletedAt(completedAt);
        round.setStatus("SUBMITTED");

        // 锁会员行：同一会员两个回合并发提交在此串行，避免同时把自己写成最好
        memberRepository.findByIdForUpdate(round.getMember().getId()).orElseThrow();
        // 先把状态变更刷入库，下面的 SUBMITTED 查询才能把本回合一并纳入同组重算
        roundRepository.flush();

        PersonalBestSupport.Eligibility eligibility = PersonalBestSupport.check(round);
        if (!eligibility.eligible()) {
            // 能提交的回合必然完整且组内对象齐备；保险起见保持无标记
            round.setPersonalBest(false);
            roundRepository.save(round);
            return toDetail(round);
        }

        // 只取同一可比组别的已完成回合重算：新回合更高分则替换旧标记，同分保留最早完成者
        List<Round> sameGroup = roundRepository
                .findByMemberIdAndStatusOrderByStartTimeDesc(round.getMember().getId(), "SUBMITTED")
                .stream()
                .filter(item -> PersonalBestSupport.check(item).eligible())
                .filter(item -> PersonalBestSupport.check(item).key().equals(eligibility.key()))
                .toList();
        PersonalBestSupport.applyBestMarks(sameGroup);
        roundRepository.saveAll(sameGroup);
        return toDetail(round);
    }

    @Transactional(readOnly = true)
    public Round require(Long id) {
        return roundRepository.findById(id)
                .orElseThrow(() -> new BizException("回合不存在：id=" + id));
    }

    /** 写操作入口：悲观锁回合行，与安全联锁的暂停 / 恢复互斥 */
    private Round requireForUpdate(Long id) {
        return roundRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("回合不存在：id=" + id));
    }

    private static void rejectIfPaused(Round round, String action) {
        if ("PAUSED".equals(round.getStatus())) {
            throw new BizException("回合 [" + round.getRoundNo() + "] 因安全停射事件暂停，放行前不能" + action);
        }
    }

    private void recalculate(Round round) {
        int total = round.getArrows().stream().mapToInt(ArrowScore::getRingValue).sum();
        round.setTotalScore(total);
        if (round.getArrows().isEmpty()) {
            round.setAverageScore(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        } else {
            round.setAverageScore(BigDecimal.valueOf(total)
                    .divide(BigDecimal.valueOf(round.getArrows().size()), 2, RoundingMode.HALF_UP));
        }
    }

    private String nextRoundNo() {
        String no = "R" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (roundRepository.existsByRoundNo(no)) {
            no = "R" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    /** Round → RoundView：带上可比组别与无法参与比较的明确原因，计分页 / 看板共用 */
    public RoundDtos.RoundView toView(Round round) {
        PersonalBestSupport.Eligibility eligibility = PersonalBestSupport.check(round);
        RoundDtos.ComparableGroupView groupView = null;
        if (eligibility.eligible()) {
            PersonalBestSupport.GroupKey key = eligibility.key();
            groupView = new RoundDtos.ComparableGroupView(
                    key.memberId(),
                    key.bowType(),
                    RangeDict.bowTypeName(key.bowType()),
                    key.distance(),
                    key.arrowCount(),
                    key.label());
        }
        return new RoundDtos.RoundView(
                round.getId(),
                round.getRoundNo(),
                round.getMember() == null ? null : round.getMember().getId(),
                round.getMember() == null ? "" : round.getMember().getName(),
                round.getMember() == null ? "" : round.getMember().getCardNo(),
                round.getLane() == null ? null : round.getLane().getId(),
                round.getLane() == null ? "" : round.getLane().getLaneNo(),
                round.getLane() == null ? null : round.getLane().getDistance(),
                round.getBowType(),
                RangeDict.bowTypeName(round.getBowType()),
                round.getStartTime(),
                round.getCompletedAt(),
                round.getArrowCount(),
                round.getArrows().size(),
                round.getTotalScore(),
                round.getAverageScore(),
                round.getPersonalBest(),
                round.getStatus(),
                RangeDict.roundStatusName(round.getStatus()),
                eligibility.eligible(),
                groupView,
                eligibility.reasonCode(),
                eligibility.reason());
    }

    private RoundDtos.RoundDetailView toDetail(Round round) {
        List<RoundDtos.ArrowView> arrows = new ArrayList<>();
        List<ArrowScore> list = round.getArrows();
        for (int index = 0; index < list.size(); index += 1) {
            ArrowScore arrow = list.get(index);
            arrows.add(new RoundDtos.ArrowView(index + 1, arrow.getRing(), arrow.getRingValue()));
        }
        return new RoundDtos.RoundDetailView(toView(round), arrows);
    }
}
