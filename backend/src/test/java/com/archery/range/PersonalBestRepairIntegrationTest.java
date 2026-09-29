package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.archery.range.service.PersonalBestRepairService;
import com.archery.range.service.RoundService;

/**
 * 个人最好成绩修复验收测试（H2 MySQL 模式加载真实 schema.sql 种子数据）：
 * 可比组别独立、12 支不压 6 支、新高分替换旧标记、同分保留最早、进行中排除、
 * 历史修复幂等且不动原始成绩、空弓种兼容默认弓种、异常记录明确列出、标记持久化。
 *
 * 用例间共用一个内存库，涉及增量回合的场景统一使用新建会员与箭道，避免互相污染。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonalBestRepairIntegrationTest {

    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private LaneRepository laneRepository;
    @Autowired
    private RoundService roundService;
    @Autowired
    private PersonalBestRepairService repairService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate tx;

    // ---------- 1. 种子数据保留旧口径标记：12 支高分回合与 6 支回合混在一起 ----------

    @Test
    @org.junit.jupiter.api.Order(1)
    void seed_legacyMarksAreMixedAcrossGroups() {
        // 会员 4：回合 3 = COMPOUND 30m 12 支 112（旧口径 PB=1），回合 9 = COMPOUND 30m 6 支 59（PB=0）
        Round twelve = getRound(3L);
        Round six = getRound(9L);
        assertThat(twelve.getTotalScore()).isEqualTo(112);
        assertThat(six.getTotalScore()).isEqualTo(59);
        assertThat(twelve.getPersonalBest()).isTrue();
        assertThat(six.getPersonalBest()).isFalse();
        RoundDtos.RoundView v12 = tx.execute(s -> roundService.toView(roundRepository.findById(3L).orElseThrow()));
        RoundDtos.RoundView v6 = tx.execute(s -> roundService.toView(roundRepository.findById(9L).orElseThrow()));
        assertThat(v12.comparable()).isTrue();
        assertThat(v6.comparable()).isTrue();
        assertThat(v12.group().arrowCount()).isEqualTo(12);
        assertThat(v6.group().arrowCount()).isEqualTo(6);
        assertThat(v12.group().label()).isNotEqualTo(v6.group().label());
    }

    // ---------- 2. 历史修复：按可比组别重算，12 支高总分不会压过 6 支组的最好 ----------

    @Test
    void repair_groupsIndependently_twelveDoesNotCrushSix() {
        RoundDtos.RepairReport report = tx.execute(s -> repairService.repair());

        // 会员 4 的两个组别各自产生一条最好：回合 3（12 支 112）与回合 9（6 支 59）
        assertThat(getRound(3L).getPersonalBest()).isTrue();
        assertThat(getRound(9L).getPersonalBest()).isTrue();

        long member4Bests = report.bests().stream()
                .filter(b -> b.group().memberId().equals(4L)).count();
        assertThat(member4Bests).isEqualTo(2);
        assertThat(report.bests()).filteredOn(b -> b.bestRound().id().equals(9L)).hasSize(1);

        // 每个可比组别恰好一条最好（在事务内遍历，避免游离实体的懒加载）
        Map<String, Long> bestsPerGroup = tx.execute(s -> {
            Map<String, Long> counts = new HashMap<>();
            for (Round r : roundRepository.findAll()) {
                if (Boolean.TRUE.equals(r.getPersonalBest())) {
                    RoundDtos.RoundView v = roundService.toView(r);
                    counts.merge(v.memberId() + "#" + v.group().label(), 1L, Long::sum);
                }
            }
            return counts;
        });
        assertThat(bestsPerGroup.values()).allSatisfy(count -> assertThat(count).isEqualTo(1L));
    }

    // ---------- 3. 同分保留最早完成的回合（独立会员 + 三条同分，反复重算不切换） ----------

    @Test
    void repair_tieKeepsEarliestCompleted() {
        Long memberId = newMember();
        Lane lane = lane(50);

        List<Long> ids = new ArrayList<>();
        ids.add(seedSubmitted(memberId, lane, "RECURVE", 58,
                List.of("10", "10", "10", "10", "9", "9"),
                LocalDateTime.of(2026, 9, 20, 10, 0)));
        ids.add(seedSubmitted(memberId, lane, "RECURVE", 58,
                List.of("X", "X", "X", "9", "10", "9"),
                LocalDateTime.of(2026, 9, 25, 10, 0)));
        ids.add(seedSubmitted(memberId, lane, "RECURVE", 58,
                List.of("9", "9", "X", "X", "X", "X"),
                LocalDateTime.of(2026, 9, 10, 10, 0))); // 最早完成

        tx.executeWithoutResult(s -> repairService.repair());
        assertThat(getRound(ids.get(0)).getPersonalBest()).isFalse();
        assertThat(getRound(ids.get(1)).getPersonalBest()).isFalse();
        assertThat(getRound(ids.get(2)).getPersonalBest()).isTrue();

        // 再执行一次（模拟重复处理）：标记不切换
        RoundDtos.RepairReport again = tx.execute(s -> repairService.repair());
        assertThat(again.marksChanged()).isEqualTo(0);
        assertThat(getRound(ids.get(2)).getPersonalBest()).isTrue();
        assertThat(getRound(ids.get(0)).getPersonalBest()).isFalse();
    }

    // ---------- 4. 进行中回合被排除，且不能持有最好标记 ----------

    @Test
    void repair_ongoingRoundsExcluded() {
        // 种子回合 10/11/12 为 ONGOING
        RoundDtos.RepairReport report = tx.execute(s -> repairService.repair());
        assertThat(getRound(10L).getPersonalBest()).isFalse();
        assertThat(getRound(11L).getPersonalBest()).isFalse();
        assertThat(getRound(12L).getPersonalBest()).isFalse();
        assertThat(report.excluded()).extracting(RoundDtos.ExcludedRoundView::id)
                .contains(10L, 11L, 12L);
        assertThat(report.excluded()).filteredOn(e -> e.id().equals(10L))
                .singleElement()
                .extracting(RoundDtos.ExcludedRoundView::reasonCode)
                .isEqualTo("NOT_SUBMITTED");
    }

    // ---------- 5. 修复幂等：第二次执行 marksChanged 恒为 0，结果一致 ----------

    @Test
    void repair_isIdempotent() {
        Map<Long, Boolean> afterFirst = tx.execute(s -> {
            repairService.repair();
            return snapshotMarks();
        });
        RoundDtos.RepairReport second = tx.execute(s -> repairService.repair());
        assertThat(second.marksChanged()).isEqualTo(0);
        assertThat(snapshotMarks()).isEqualTo(afterFirst);

        RoundDtos.RepairReport preview = tx.execute(s -> repairService.preview());
        assertThat(preview.applied()).isFalse();
        assertThat(preview.marksChanged()).isEqualTo(0);
    }

    // ---------- 6. 修复只改标记：箭支顺序 / 环值 / 总分 / 平均环 / 完成时间原样保留 ----------

    @Test
    void repair_doesNotTouchScoresOrArrowsOrTimes() {
        Map<Long, Snapshot> before = tx.execute(s -> snapshotAll());

        tx.executeWithoutResult(s -> repairService.repair());

        Map<Long, Snapshot> after = tx.execute(s -> snapshotAll());
        for (Map.Entry<Long, Snapshot> entry : before.entrySet()) {
            Snapshot b = entry.getValue();
            Snapshot a = after.get(entry.getKey());
            assertThat(a.roundNo()).isEqualTo(b.roundNo());
            assertThat(a.arrowCount()).isEqualTo(b.arrowCount());
            assertThat(a.total()).isEqualTo(b.total());
            assertThat(a.avg()).isEqualByComparingTo(b.avg());
            assertThat(a.start()).isEqualTo(b.start());
            assertThat(a.completed()).isEqualTo(b.completed());
            assertThat(a.rings()).containsExactlyElementsOf(b.rings());
        }
    }

    // ---------- 7. 新回合提交：同组新高分替换旧标记；同分不切换；另一射距组不受影响 ----------

    @Test
    void submit_higherScoreReplacesOldMarkWithinGroupOnly() {
        Long memberId = newMember();
        Lane lane50 = lane(50);
        Lane lane10 = lane(10);

        // 50m 组已有一条 58 分的最早最好；10m 组一条 55 分的最好
        Long old50 = seedSubmitted(memberId, lane50, "RECURVE", 58,
                List.of("X", "X", "X", "9", "10", "9"), LocalDateTime.of(2026, 9, 1, 10, 0));
        Long best10 = seedSubmitted(memberId, lane10, "RECURVE", 55,
                List.of("10", "9", "9", "9", "9", "9"), LocalDateTime.of(2026, 9, 2, 10, 0));
        tx.executeWithoutResult(s -> repairService.repair());
        assertThat(getRound(old50).getPersonalBest()).isTrue();

        // 新开一个 59 分的 6 支回合（真实提交链路）
        Long newId = tx.execute(s -> {
            Member m = memberRepository.findById(memberId).orElseThrow();
            Round r = newOngoingRound(m, lane50, "RECURVE", List.of("10", "10", "10", "10", "10", "9"));
            return roundRepository.save(r).getId();
        });
        submit(newId);

        assertThat(getRound(newId).getPersonalBest()).isTrue();
        assertThat(getRound(old50).getPersonalBest()).isFalse(); // 同组旧标记被清除
        assertThat(getRound(best10).getPersonalBest()).isTrue(); // 10m 另一组不受影响

        // 再打一个同分 59 的新回合：完成更晚，不能抢走标记（重复处理也不切换）
        Long tieId = tx.execute(s -> {
            Member m = memberRepository.findById(memberId).orElseThrow();
            Round r = newOngoingRound(m, lane50, "RECURVE", List.of("9", "10", "10", "10", "10", "10"));
            return roundRepository.save(r).getId();
        });
        submit(tieId);
        assertThat(getRound(tieId).getPersonalBest()).isFalse();
        assertThat(getRound(newId).getPersonalBest()).isTrue();
    }

    // ---------- 8. 新回合属于新可比组别：12 支高分不影响 6 支组的最好 ----------

    @Test
    void submit_differentArrowCountGroupGetsOwnBest() {
        Long memberId = newMember();
        Lane lane = lane(30);

        Long old6 = seedSubmitted(memberId, lane, "COMPOUND", 59,
                List.of("X", "X", "X", "X", "X", "9"), LocalDateTime.of(2026, 9, 1, 10, 0));
        Long old12 = seedSubmitted(memberId, lane, "COMPOUND", 112, twelve(112),
                LocalDateTime.of(2026, 9, 2, 10, 0));
        tx.executeWithoutResult(s -> repairService.repair());
        assertThat(getRound(old6).getPersonalBest()).isTrue();
        assertThat(getRound(old12).getPersonalBest()).isTrue();

        Long id = tx.execute(s -> {
            Member m = memberRepository.findById(memberId).orElseThrow();
            Round r = newOngoingRound(m, lane, "COMPOUND",
                    List.of("10", "10", "10", "10", "10", "10", "10", "10", "10", "10", "10", "10"));
            return roundRepository.save(r).getId();
        });
        submit(id);

        assertThat(getRound(id).getPersonalBest()).isTrue();
        assertThat(getRound(old6).getPersonalBest()).isTrue();   // 6 支组最好不动
        assertThat(getRound(old12).getPersonalBest()).isFalse(); // 同 12 支组旧最高被替换
    }

    // ---------- 9. 箭数不完整不能提交，提交失败绝不改变最好标记 ----------

    @Test
    void submit_rejectsIncompleteRoundAndKeepsMarks() {
        Long memberId = newMember();
        Lane lane = lane(50);
        Long oldBest = seedSubmitted(memberId, lane, "RECURVE", 58,
                List.of("X", "X", "X", "9", "10", "9"), LocalDateTime.of(2026, 9, 1, 10, 0));
        tx.executeWithoutResult(s -> repairService.repair());

        Long id = tx.execute(s -> {
            Member m = memberRepository.findById(memberId).orElseThrow();
            Round r = new Round();
            r.setRoundNo(uniqueRoundNo());
            r.setMember(m);
            r.setLane(lane);
            r.setBowType("RECURVE");
            r.setStartTime(LocalDateTime.now());
            r.setArrowCount(6);
            r.setTotalScore(18);
            r.setAverageScore(new BigDecimal("9.00"));
            r.setPersonalBest(false);
            r.setStatus("ONGOING");
            r.setArrows(new ArrayList<>(List.of(new ArrowScore("9", 9), new ArrowScore("9", 9))));
            return roundRepository.save(r).getId();
        });
        assertThatThrownBy(() -> submit(id)).isInstanceOf(BizException.class).hasMessageContaining("支箭");

        Round failed = getRound(id);
        assertThat(failed.getStatus()).isEqualTo("ONGOING");
        assertThat(failed.getCompletedAt()).isNull();
        assertThat(failed.getPersonalBest()).isFalse();
        assertThat(getRound(oldBest).getPersonalBest()).isTrue();
    }

    // ---------- 10. 历史空弓种按默认弓种 RECURVE 兼容，仍可参与比较 ----------

    @Test
    void repair_blankBowTypeDefaultsToRecurve() {
        Long memberId = newMember();
        Lane lane = lane(50);
        Long base = seedSubmitted(memberId, lane, "RECURVE", 58,
                List.of("X", "X", "X", "9", "10", "9"), LocalDateTime.of(2026, 9, 19, 10, 0));
        // 空串弓种：同分但更晚 → 视为 RECURVE 同组，不抢标记
        Long blankLater = seedSubmitted(memberId, lane, "", 58,
                List.of("9", "10", "10", "X", "X", "9"), LocalDateTime.of(2026, 9, 25, 10, 0));
        // 纯空白弓种：更高分 60 → 同组，成为最好
        Long blankHigher = seedSubmitted(memberId, lane, "   ", 60,
                List.of("X", "X", "X", "X", "X", "X"), LocalDateTime.of(2026, 9, 26, 10, 0));

        RoundDtos.RepairReport report = tx.execute(s2 -> repairService.repair());
        assertThat(getRound(base).getPersonalBest()).isFalse();
        assertThat(getRound(blankLater).getPersonalBest()).isFalse();
        assertThat(getRound(blankHigher).getPersonalBest()).isTrue();
        RoundDtos.RoundView v = tx.execute(s2 -> roundService.toView(getRound(blankHigher)));
        assertThat(v.comparable()).isTrue();
        assertThat(v.group().bowType()).isEqualTo("RECURVE");

        assertThat(report.excluded()).extracting(RoundDtos.ExcludedRoundView::id)
                .doesNotContain(base, blankLater, blankHigher);
    }

    // ---------- 11. 异常历史记录：射距非法 / 弓种无法识别 / 箭数不完整 / 关联缺失，明确列出 ----------

    @Test
    void repair_anomaliesListedExplicitlyAndNotSilentlyGrouped() {
        Long memberId = newMember();
        Lane lane = lane(50);

        // 非法射距用独立箭道承载，不污染共享箭道
        Lane weirdLane = lane(77);

        Long unknownBow = seedSubmitted(memberId, lane, "LONGBOW", 60,
                List.of("X", "X", "X", "X", "X", "X"), LocalDateTime.of(2026, 9, 21, 10, 0));
        Long incomplete = tx.execute(s -> {
            Member m = memberRepository.findById(memberId).orElseThrow();
            Round r = new Round();
            r.setRoundNo(uniqueRoundNo());
            r.setMember(m);
            r.setLane(lane);
            r.setBowType("RECURVE");
            r.setStartTime(LocalDateTime.of(2026, 9, 27, 10, 0));
            r.setCompletedAt(LocalDateTime.of(2026, 9, 27, 11, 0));
            r.setArrowCount(12); // 规定 12 支，只打了 6 支
            r.setTotalScore(55);
            r.setAverageScore(new BigDecimal("9.17"));
            r.setPersonalBest(false);
            r.setStatus("SUBMITTED");
            r.setArrows(new ArrayList<>(List.of(
                    new ArrowScore("10", 10), new ArrowScore("10", 10),
                    new ArrowScore("10", 10), new ArrowScore("10", 10),
                    new ArrowScore("10", 10), new ArrowScore("5", 5))));
            return roundRepository.save(r).getId();
        });
        Long badDistance = seedSubmitted(memberId, weirdLane, "RECURVE", 60,
                List.of("X", "X", "X", "X", "X", "X"), LocalDateTime.of(2026, 9, 22, 10, 0));
        // 给三类异常回合都带上旧口径脏 PB
        tx.executeWithoutResult(s -> roundRepository
                .findAllById(List.of(unknownBow, incomplete, badDistance))
                .forEach(r -> r.setPersonalBest(true)));
        // 关联对象缺失：绕过外键直接插悬空行
        Long laneMissing = insertDanglingRound(memberId, 999001L);
        Long memberMissing = insertDanglingRound(999002L, lane.getId());

        RoundDtos.RepairReport report = tx.execute(s2 -> repairService.repair());

        assertThat(getRound(unknownBow).getPersonalBest()).isFalse();
        assertThat(getRound(unknownBow).getTotalScore()).isEqualTo(60);
        assertThat(getRound(incomplete).getPersonalBest()).isFalse();
        assertThat(getRound(incomplete).getTotalScore()).isEqualTo(55);
        assertThat(getRound(badDistance).getPersonalBest()).isFalse();
        // 悬空行无法走实体检索（关联缺失），用标量查询核对标记被清除、成绩保留
        assertThat(markOf(laneMissing)).isFalse();
        assertThat(markOf(memberMissing)).isFalse();
        assertThat(totalOf(laneMissing)).isEqualTo(50);

        Map<Long, String> reasonById = new HashMap<>();
        report.excluded().forEach(e -> reasonById.put(e.id(), e.reasonCode()));
        assertThat(reasonById.get(unknownBow)).isEqualTo("BOW_TYPE_UNKNOWN");
        assertThat(reasonById.get(incomplete)).isEqualTo("INCOMPLETE_ARROWS");
        assertThat(reasonById.get(badDistance)).isEqualTo("DISTANCE_UNKNOWN");
        assertThat(reasonById.get(laneMissing)).isEqualTo("LANE_MISSING");
        assertThat(reasonById.get(memberMissing)).isEqualTo("MEMBER_MISSING");

        assertThat(report.bests()).extracting(b -> b.bestRound().id())
                .doesNotContain(unknownBow, incomplete, badDistance, laneMissing, memberMissing);
    }

    // ---------- 12. 会员详情按可比组别列出 PB；打开最好回合能看到完整箭支 ----------

    @Test
    void memberBests_groupedAndDetailKeepsAllArrows() {
        // 种子会员 4 有 COMPOUND 30m 的 6 支（回合 9）与 12 支（回合 3）两个组别
        RoundDtos.MemberBestsView bests = tx.execute(s -> repairService.memberBests(4L));
        assertThat(bests.groups()).hasSize(2);
        assertThat(bests.groups()).extracting(g -> g.group().arrowCount()).containsExactlyInAnyOrder(6, 12);
        for (RoundDtos.BestGroupView g : bests.groups()) {
            RoundDtos.RoundDetailView detail = tx.execute(s2 -> roundService.detail(g.bestRound().id()));
            assertThat(detail.arrows()).hasSize(g.group().arrowCount());
            List<Integer> expected = new ArrayList<>();
            for (int i = 1; i <= g.group().arrowCount(); i += 1) {
                expected.add(i);
            }
            assertThat(detail.arrows()).extracting(RoundDtos.ArrowView::shotIndex)
                    .containsExactlyElementsOf(expected);
        }
    }

    // ---------- 13. 持久化：全新事务读取（模拟刷新 / 重启）标记不变 ----------

    @Test
    void marksSurviveReload() {
        tx.executeWithoutResult(s -> repairService.repair());
        Map<Long, Boolean> marks = snapshotMarks();
        Map<Long, Boolean> reloaded = tx.execute(s -> snapshotMarks());
        assertThat(reloaded).isEqualTo(marks);
        assertThat(getRound(1L).getPersonalBest()).isTrue();
    }

    // ---------- helpers ----------

    private Round getRound(Long id) {
        return tx.execute(s -> roundRepository.findById(id).orElseThrow());
    }

    private boolean markOf(Long id) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT personal_best FROM rounds WHERE id = ?", Integer.class, id);
        return v != null && v == 1;
    }

    private Integer totalOf(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT total_score FROM rounds WHERE id = ?", Integer.class, id);
    }

    private void submit(Long id) {
        tx.executeWithoutResult(s -> roundService.submit(id));
    }

    private Map<Long, Boolean> snapshotMarks() {
        Map<Long, Boolean> map = new HashMap<>();
        for (Round r : roundRepository.findAll()) {
            map.put(r.getId(), Boolean.TRUE.equals(r.getPersonalBest()));
        }
        return map;
    }

    /** 事务内快照全部回合的原始成绩字段与箭支顺序 */
    private Map<Long, Snapshot> snapshotAll() {
        Map<Long, Snapshot> map = new HashMap<>();
        for (Round r : roundRepository.findAll()) {
            map.put(r.getId(), new Snapshot(r.getRoundNo(), r.getArrowCount(), r.getTotalScore(),
                    r.getAverageScore(), r.getStartTime(), r.getCompletedAt(),
                    r.getArrows().stream().map(ArrowScore::getRing).toList()));
        }
        return map;
    }

    private record Snapshot(String roundNo, Integer arrowCount, Integer total, BigDecimal avg,
                            LocalDateTime start, LocalDateTime completed, List<String> rings) {
    }

    private static long memberSeq = 9000;
    private static long laneSeq = 900;
    private static final AtomicLong ROUND_SEQ = new AtomicLong();

    /** 生成不超过 24 字符的唯一回合号 */
    private static String uniqueRoundNo() {
        return "RT" + ROUND_SEQ.incrementAndGet();
    }

    private Long newMember() {
        memberSeq += 1;
        long seq = memberSeq;
        Member m = new Member();
        m.setCardNo("AR-TEST-" + seq);
        m.setName("测试会员" + seq);
        m.setPhone("139" + String.format("%08d", seq));
        m.setLevel("NORMAL");
        m.setBalance(new BigDecimal("1000.00"));
        m.setRegisterDate(LocalDate.of(2026, 9, 1));
        m.setTotalSpend(BigDecimal.ZERO);
        return memberRepository.save(m).getId();
    }

    private Lane lane(int distance) {
        laneSeq += 1;
        long seq = laneSeq;
        Lane lane = new Lane();
        lane.setLaneNo("T" + seq);
        lane.setDistance(distance);
        lane.setTargetType("纸质靶");
        lane.setStatus("OPEN");
        lane.setHourlyPrice(new BigDecimal("60.00"));
        return laneRepository.save(lane);
    }

    private Long seedSubmitted(Long memberId, Lane lane, String bowType, int total,
            List<String> rings, LocalDateTime completed) {
        return tx.execute(s -> {
            Member m = memberRepository.findById(memberId).orElseThrow();
            Round r = newSubmittedRound(m, lane, bowType, rings);
            r.setTotalScore(total);
            r.setStartTime(completed.minusMinutes(40));
            r.setCompletedAt(completed);
            return roundRepository.save(r).getId();
        });
    }

    private Round newSubmittedRound(Member member, Lane lane, String bowType, List<String> rings) {
        Round r = buildRound(member, lane, bowType, rings);
        LocalDateTime start = LocalDateTime.of(2026, 9, 27, 12, 0);
        r.setStartTime(start);
        r.setCompletedAt(start.plusMinutes(30));
        r.setStatus("SUBMITTED");
        return r;
    }

    private Round newOngoingRound(Member member, Lane lane, String bowType, List<String> rings) {
        Round r = buildRound(member, lane, bowType, rings);
        r.setStartTime(LocalDateTime.now());
        r.setCompletedAt(null);
        r.setStatus("ONGOING");
        return r;
    }

    private Round buildRound(Member member, Lane lane, String bowType, List<String> rings) {
        Round r = new Round();
        r.setRoundNo(uniqueRoundNo());
        r.setMember(member);
        r.setLane(lane);
        r.setBowType(bowType == null ? "" : bowType);
        r.setArrowCount(rings.size());
        List<ArrowScore> arrows = new ArrayList<>();
        int total = 0;
        for (String ring : rings) {
            int value = RangeDict.ringValue(ring);
            arrows.add(new ArrowScore(ring, value));
            total += value;
        }
        r.setArrows(arrows);
        r.setTotalScore(total);
        r.setAverageScore(BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(rings.size()), 2, RoundingMode.HALF_UP));
        r.setPersonalBest(false);
        return r;
    }

    private List<String> twelve(int total) {
        int tens = total / 10;
        int rest = total % 10;
        List<String> rings = new ArrayList<>();
        for (int i = 0; i < tens; i += 1) {
            rings.add("10");
        }
        if (rest > 0) {
            rings.add(String.valueOf(rest));
        }
        while (rings.size() < 12) {
            rings.add("M");
        }
        return rings;
    }

    /** 绕过外键约束插入关联对象悬空的历史回合（模拟损坏的旧数据） */
    private Long insertDanglingRound(Long memberId, Long laneId) {
        String roundNo = uniqueRoundNo();
        return jdbcTemplate.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET REFERENTIAL_INTEGRITY FALSE");
            }
            try (java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rounds (round_no, member_id, lane_id, bow_type, start_time, completed_at, "
                            + "arrow_count, total_score, average_score, personal_best, status) "
                            + "VALUES (?, ?, ?, 'RECURVE', '2026-09-03 10:00:00', '2026-09-03 11:00:00', "
                            + "6, 50, 8.33, 1, 'SUBMITTED')")) {
                ps.setString(1, roundNo);
                ps.setLong(2, memberId);
                ps.setLong(3, laneId);
                ps.executeUpdate();
            }
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
            try (java.sql.PreparedStatement ps = con.prepareStatement(
                    "SELECT id FROM rounds WHERE round_no = ?")) {
                ps.setString(1, roundNo);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }
}
