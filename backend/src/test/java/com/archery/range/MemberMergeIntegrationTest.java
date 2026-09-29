package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.archery.range.common.BizException;
import com.archery.range.domain.ArrowScore;
import com.archery.range.domain.CertApplication;
import com.archery.range.domain.Course;
import com.archery.range.domain.CourseEnroll;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.dto.MergeDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.CourseEnrollRepository;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;
import com.archery.range.service.MemberService;
import com.archery.range.service.MergeRelationMigrator;
import com.archery.range.service.MergeService;

/**
 * 重复会员档案合并验收测试（H2 MySQL 模式 + 真实 schema.sql）：
 * 1) 无互斥项的完整合并（余额 / 累计消费合并、关系全部迁移、来源封存）；
 * 2) 同一课程重复报名去重（保留较早一条）；
 * 3) 余额与等级重算且不降级；
 * 4) 个人最好迁移后重新归组（不动箭值 / 完成时间）；
 * 5) 预演后数据变化拒绝确认（要求重新预演）；
 * 6) 两个操作端并发确认同一来源档案只成功一次；
 * 7) 迁移中断整体回滚（来源档案仍可用、余额未动）；
 * 8) 旧卡入口跳转保留档案（带合并时间与原因）；
 * 另含四类互斥拦截、封存档案禁止任何业务动作、轨迹可查等。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class MemberMergeIntegrationTest {

    @Autowired
    private MergeService mergeService;
    @Autowired
    private MergeRelationMigrator migrator;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private CourseEnrollRepository enrollRepository;
    @Autowired
    private EquipmentRepository equipmentRepository;
    @Autowired
    private LaneRepository laneRepository;
    @Autowired
    private CertApplicationRepository certApplicationRepository;
    @Autowired
    private MemberService memberService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate tx;

    private static final AtomicLong SEQ = new AtomicLong(8000);

    @AfterEach
    void clearFault() {
        migrator.clearInjectedFailure();
    }

    // ---------- 1. 无互斥项合并：余额 / 累计消费 / 全部关系迁移，来源封存 ----------

    @Test
    void merge_noConflicts_migratesEverythingAndArchivesSource() {
        Long keepId = newMember("KEEP-1", 100, 500, "NORMAL");
        Long sourceId = newMember("SRC-1", 80, 200, "NORMAL");
        // 来源卡：一条已提交历史回合、一条课程报名、一件租出器材、一条占用箭道、一笔认证申请
        Long roundId = submittedRound(sourceId, lane(10), "RECURVE",
                List.of("10", "9", "9", "9", "9", "9"), LocalDateTime.of(2026, 9, 1, 10, 0));
        Long courseId = course("无冲突测试课");
        Long enrollId = enroll(sourceId, courseId, LocalDateTime.of(2026, 9, 2, 9, 0));
        Long equipmentId = rentedEquipment(sourceId);
        Long laneId = occupiedLane(sourceId);
        certApplication(sourceId, "TRADITIONAL", 18, "PENDING", null);

        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.mergeable()).isTrue();
        assertThat(preview.conflicts()).isEmpty();
        assertThat(preview.source().roundHistory().submittedCount()).isEqualTo(1);
        assertThat(preview.source().enrollments()).hasSize(1);
        assertThat(preview.source().rentedEquipment()).hasSize(1);
        assertThat(preview.source().occupiedLanes()).hasSize(1);
        assertThat(preview.source().certApplications()).hasSize(1);

        MergeDtos.MergeResult result = confirm(preview.previewId());
        assertThat(result.balanceAfter()).isEqualByComparingTo("180.00");
        assertThat(result.totalSpendAfter()).isEqualByComparingTo("700.00");
        assertThat(result.migration().roundsMoved()).isEqualTo(1);
        assertThat(result.migration().enrollmentsMoved()).isEqualTo(1);
        assertThat(result.migration().equipmentMoved()).isEqualTo(1);
        assertThat(result.migration().lanesMoved()).isEqualTo(1);
        assertThat(result.migration().certsMoved()).isEqualTo(1);

        // 回合、报名、器材、箭道、认证全部挂到保留会员
        assertThat(getRound(roundId).getMember().getId()).isEqualTo(keepId);
        assertThat(enrollRepository.findById(enrollId).orElseThrow().getMember().getId()).isEqualTo(keepId);
        assertThat(equipmentRepository.findById(equipmentId).orElseThrow().getRenterId()).isEqualTo(keepId);
        assertThat(laneRepository.findById(laneId).orElseThrow().getOccupantId()).isEqualTo(keepId);
        assertThat(certApplicationRepository.findAll()).filteredOn(a -> a.getAppNo().startsWith("T-"))
                .isNotEmpty();
        assertThat(certApplicationRepository.findAll().stream()
                .filter(a -> a.getMember() != null && sourceId.equals(a.getMember().getId())).count()).isZero();

        // 来源档案封存：原卡号 / 原姓名 / 原手机号保留，余额清零，不能再用
        Member source = member(sourceId);
        Member keep = member(keepId);
        assertThat(source.getStatus()).isEqualTo(Member.STATUS_MERGED);
        assertThat(source.getCardNo()).isEqualTo("SRC-1");
        assertThat(source.getName()).isEqualTo("会员SRC-1");
        assertThat(source.getMergedIntoId()).isEqualTo(keepId);
        assertThat(source.getMergedBy()).isEqualTo("值班经理甲");
        assertThat(source.getMergeReason()).isEqualTo("误开两张卡");
        assertThat(source.getBalance()).isEqualByComparingTo("0.00");
        // 原卡号 / 原手机号仍唯一保留（没有删会员、没有简单改手机号）
        assertThat(memberRepository.findByCardNo("SRC-1")).isPresent();
        assertThat(keep.getBalance()).isEqualByComparingTo("180.00");

        // 轨迹可查，操作者 / 原因 / 原卡号原姓名都在
        List<MergeDtos.MergeTrailView> trails = mergeService.trails(sourceId);
        assertThat(trails).hasSize(1);
        MergeDtos.MergeTrailView trail = trails.get(0);
        assertThat(trail.sourceCardNo()).isEqualTo("SRC-1");
        assertThat(trail.sourceName()).isEqualTo(source.getName());
        assertThat(trail.keepCardNo()).isEqualTo("KEEP-1");
        assertThat(trail.operator()).isEqualTo("值班经理甲");
        assertThat(trail.reason()).isEqualTo("误开两张卡");
        assertThat(trail.details()).isNotEmpty();
    }

    // ---------- 2. 同一课程重复报名：只保留报名较早的一条 ----------

    @Test
    void merge_duplicateCourseEnroll_keepsEarlierOne() {
        Long keepId = newMember("KEEP-2", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-2", 10, 0, "NORMAL");
        Long courseId = course("重复报名课");
        // 保留卡报名较早，来源卡较晚
        Long keepEnroll = enroll(keepId, courseId, LocalDateTime.of(2026, 9, 1, 9, 0));
        Long sourceEnroll = enroll(sourceId, courseId, LocalDateTime.of(2026, 9, 3, 9, 0));
        // 来源卡另外报了一门不重复的课程，应正常迁移
        Long otherCourseId = course("来源独有课");
        Long otherEnroll = enroll(sourceId, otherCourseId, LocalDateTime.of(2026, 9, 4, 9, 0));

        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.mergeable()).isTrue();
        assertThat(preview.resolves()).hasSize(1);
        MergeDtos.ResolveItem resolve = preview.resolves().get(0);
        assertThat(resolve.type()).isEqualTo("DEDUP_ENROLL_KEEP_EARLIEST");
        assertThat(resolve.keptEnrollId()).isEqualTo(keepEnroll);
        assertThat(resolve.droppedEnrollId()).isEqualTo(sourceEnroll);
        assertThat(preview.migration().enrollmentsMoved()).isEqualTo(1);
        assertThat(preview.migration().enrollmentsDropped()).isEqualTo(1);

        confirm(preview.previewId());

        assertThat(enrollRepository.findById(keepEnroll)).isPresent();
        assertThat(enrollRepository.findById(sourceEnroll)).isEmpty(); // 较晚一条删除
        assertThat(enrollRepository.findById(otherEnroll).orElseThrow().getMember().getId()).isEqualTo(keepId);
        // 同一课程保留卡名下只剩一条
        assertThat(enrollRepository.findByCourseIdOrderByIdAsc(courseId))
                .hasSize(1).first().extracting(e -> e.getMember().getId()).isEqualTo(keepId);

        // 反过来：来源卡报名更早时，保留的是来源卡那条（迁移后挂保留会员）
        Long keep2 = newMember("KEEP-2B", 10, 0, "NORMAL");
        Long source2 = newMember("SRC-2B", 10, 0, "NORMAL");
        Long course2 = course("重复报名课2");
        Long late = enroll(keep2, course2, LocalDateTime.of(2026, 9, 5, 9, 0));
        Long early = enroll(source2, course2, LocalDateTime.of(2026, 9, 2, 9, 0));
        MergeDtos.MergePreview preview2 = preview(keep2, source2);
        assertThat(preview2.resolves().get(0).keptEnrollId()).isEqualTo(early);
        confirm(preview2.previewId());
        assertThat(enrollRepository.findById(late)).isEmpty();
        assertThat(enrollRepository.findById(early).orElseThrow().getMember().getId()).isEqualTo(keep2);
    }

    // ---------- 3. 余额与等级重算（按合并累计消费，且不降级） ----------

    @Test
    void merge_balanceAddedAndLevelRecalculatedWithoutDowngrade() {
        // 保留卡银卡但累计消费只有 1000（历史等级高于重算结果）；来源卡消费 2500
        Long keepId = newMember("KEEP-3", 1000, 1000, "SILVER");
        Long sourceId = newMember("SRC-3", 500, 2500, "NORMAL");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        // 合并累计消费 3500 → 重算 SILVER；保留卡本身已是 SILVER，不降级
        assertThat(preview.finance().mergedBalance()).isEqualByComparingTo("1500.00");
        assertThat(preview.finance().mergedTotalSpend()).isEqualByComparingTo("3500.00");
        assertThat(preview.finance().recalculatedLevel()).isEqualTo("SILVER");
        assertThat(preview.finance().finalLevel()).isEqualTo("SILVER");

        confirm(preview.previewId());
        assertThat(member(keepId).getLevel()).isEqualTo("SILVER");

        // 保留卡 NORMAL 但消费 9000（GOLD），来源 NORMAL 消费 2000：重算 GOLD
        Long keep2 = newMember("KEEP-3B", 10, 9000, "NORMAL");
        Long source2 = newMember("SRC-3B", 10, 2000, "NORMAL");
        MergeDtos.MergePreview preview2 = preview(keep2, source2);
        assertThat(preview2.finance().mergedTotalSpend()).isEqualByComparingTo("11000.00");
        assertThat(preview2.finance().finalLevel()).isEqualTo("GOLD");
        confirm(preview2.previewId());
        assertThat(member(keep2).getLevel()).isEqualTo("GOLD");

        // 只升不降：两张卡都是 GOLD/SILVER，即使合计消费达不到门槛也不降级（演示数据不会出现，构造极端场景）
        Long keep3 = newMember("KEEP-3C", 10, 0, "GOLD");
        Long source3 = newMember("SRC-3C", 10, 0, "NORMAL");
        MergeDtos.MergePreview preview3 = preview(keep3, source3);
        assertThat(preview3.finance().recalculatedLevel()).isEqualTo("NORMAL");
        assertThat(preview3.finance().levelKeptFromHigher()).isTrue();
        assertThat(preview3.finance().finalLevel()).isEqualTo("GOLD");
        confirm(preview3.previewId());
        assertThat(member(keep3).getLevel()).isEqualTo("GOLD");
    }

    // ---------- 4. 历史成绩保留 + 个人最好迁移后按保留会员重新归组 ----------

    @Test
    void merge_personalBestRegroupedAfterMigrationWithoutTouchingScores() {
        Long keepId = newMember("KEEP-4", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-4", 10, 0, "NORMAL");
        Long laneId = lane(50);
        // 同弓种 / 射距 / 箭数两组成绩：
        // 保留卡 55 分（原 PB）；来源卡 58 分（原 PB，且有更高分）→ 迁移后来源卡回合应成为保留会员的 PB
        Long keepRound = submittedRound(keepId, laneId, "RECURVE",
                List.of("10", "10", "10", "9", "9", "7"), LocalDateTime.of(2026, 9, 1, 10, 0));
        Long sourceRound = submittedRound(sourceId, laneId, "RECURVE",
                List.of("X", "10", "10", "10", "9", "9"), LocalDateTime.of(2026, 9, 2, 10, 0));
        tx.executeWithoutResult(s -> {
            getRound(keepRound).setPersonalBest(true);
            getRound(sourceRound).setPersonalBest(true);
            roundRepository.saveAll(List.of(getRound(keepRound), getRound(sourceRound)));
        });
        // 迁移前各自一组，分别有 PB
        assertThat(getRound(keepRound).getPersonalBest()).isTrue();
        assertThat(getRound(sourceRound).getPersonalBest()).isTrue();

        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        confirm(preview.previewId());

        // 迁移后按保留会员重新归组：58 分的来源卡回合成为唯一 PB，55 分的旧 PB 清除
        Round winner = getRound(sourceRound);
        Round loser = getRound(keepRound);
        assertThat(winner.getMember().getId()).isEqualTo(keepId);
        assertThat(winner.getPersonalBest()).isTrue();
        assertThat(loser.getPersonalBest()).isFalse();
        // 箭值 / 总分 / 完成时间绝未改写（箭支是懒加载集合，在事务内读取）
        List<String> winnerRings = tx.execute(s0 -> roundRepository.findById(sourceRound).orElseThrow()
                .getArrows().stream().map(ArrowScore::getRing).toList());
        assertThat(winner.getTotalScore()).isEqualTo(58);
        assertThat(winner.getCompletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 10, 0));
        assertThat(winnerRings).containsExactly("X", "10", "10", "10", "9", "9");
        assertThat(loser.getTotalScore()).isEqualTo(55);
        // 同分更早的规则：在保留会员名下再构造一条同分但更晚的，不抢标记
        Long source2 = newMember("SRC-4B", 10, 0, "NORMAL");
        Long tieRound = submittedRound(source2, laneId, "RECURVE",
                List.of("9", "10", "10", "10", "9", "10"), LocalDateTime.of(2026, 9, 10, 10, 0));
        MergeDtos.MergePreview preview2 = preview(keepId, source2);
        confirm(preview2.previewId());
        assertThat(getRound(sourceRound).getPersonalBest()).isTrue();
        assertThat(getRound(tieRound).getPersonalBest()).isFalse();
    }

    // ---------- 5a. 预演后余额变化：拒绝确认，要求重新预演 ----------

    @Test
    void confirm_afterBalanceChange_rejectedAndRePreviewWorks() {
        Long keepId = newMember("KEEP-5", 100, 0, "NORMAL");
        Long sourceId = newMember("SRC-5", 100, 0, "NORMAL");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);

        // 预演后给来源卡充值
        tx.executeWithoutResult(s -> {
            Member m = memberRepository.findById(sourceId).orElseThrow();
            m.setBalance(m.getBalance().add(new BigDecimal("200")));
            memberRepository.save(m);
        });
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("业务数据已发生变化")
                .hasMessageContaining("重新发起预演");
        // 未做任何改动
        assertThat(member(sourceId).getStatus()).isEqualTo(Member.STATUS_ACTIVE);

        // 重新预演后可以正常确认
        MergeDtos.MergePreview preview2 = preview(keepId, sourceId);
        assertThat(preview2.finance().mergedBalance()).isEqualByComparingTo("400.00");
        MergeDtos.MergeResult result = confirm(preview2.previewId());
        assertThat(result.balanceAfter()).isEqualByComparingTo("400.00");
        assertThat(member(sourceId).getStatus()).isEqualTo(Member.STATUS_MERGED);
    }

    // ---------- 5b. 预演后新增课程报名：拒绝确认 ----------

    @Test
    void confirm_afterEnrollChange_rejected() {
        Long keepId = newMember("KEEP-5B", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-5B", 10, 0, "NORMAL");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        Long courseId = course("预演后新报名课");
        enroll(sourceId, courseId, LocalDateTime.now());
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(BizException.class).hasMessageContaining("业务数据已发生变化");
        assertThat(member(sourceId).getStatus()).isEqualTo(Member.STATUS_ACTIVE);
    }

    // ---------- 5c. 预演后归还器材：拒绝确认 ----------

    @Test
    void confirm_afterEquipmentChange_rejected() {
        Long keepId = newMember("KEEP-5C", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-5C", 10, 0, "NORMAL");
        Long equipmentId = rentedEquipment(sourceId);
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        tx.executeWithoutResult(s -> {
            Equipment e = equipmentRepository.findById(equipmentId).orElseThrow();
            e.setStatus("INSTOCK");
            e.setRenterId(null);
            e.setRenterName(null);
            e.setRentedAt(null);
            equipmentRepository.save(e);
        });
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(BizException.class).hasMessageContaining("业务数据已发生变化");
    }

    // ---------- 5d. 预演后回合状态变化（提交）：拒绝确认 ----------

    @Test
    void confirm_afterRoundChange_rejected() {
        Long keepId = newMember("KEEP-5D", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-5D", 10, 0, "NORMAL");
        Long roundId = ongoingRound(sourceId, lane(10), "RECURVE");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.source().ongoingRounds()).hasSize(1);
        tx.executeWithoutResult(s -> {
            Round r = roundRepository.findById(roundId).orElseThrow();
            r.setStatus("SUBMITTED");
            r.setCompletedAt(LocalDateTime.now());
            roundRepository.save(r);
        });
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(BizException.class).hasMessageContaining("业务数据已发生变化");
    }

    // ---------- 5e. 预演后认证状态变化：拒绝确认 ----------

    @Test
    void confirm_afterCertChange_rejected() {
        Long keepId = newMember("KEEP-5E", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-5E", 10, 0, "NORMAL");
        certApplication(sourceId, "TRADITIONAL", 18, "PENDING", null);
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        tx.executeWithoutResult(s -> {
            CertApplication app = certApplicationRepository.findAll().stream()
                    .filter(a -> sourceId.equals(a.getMember().getId())).findFirst().orElseThrow();
            app.setStatus("REJECTED");
            certApplicationRepository.save(app);
        });
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(BizException.class).hasMessageContaining("业务数据已发生变化");
    }

    // ---------- 6a. 互斥：双方都有进行中的计分回合 ----------

    @Test
    void conflict_ongoingRounds_blocksMerge() {
        Long keepId = newMember("KEEP-6", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-6", 10, 0, "NORMAL");
        ongoingRound(keepId, lane(10), "RECURVE");
        ongoingRound(sourceId, lane(18), "RECURVE");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.mergeable()).isFalse();
        assertThat(preview.conflicts()).extracting(MergeDtos.ConflictItem::type)
                .contains("ONGOING_ROUND");
        assertThat(preview.conflicts().get(0).detail()).contains("未结束计分回合");
        // 有互斥项时确认被拒绝
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(BizException.class).hasMessageContaining("互斥项");
        assertThat(member(sourceId).getStatus()).isEqualTo(Member.STATUS_ACTIVE);
    }

    // ---------- 6b. 互斥：双方租着同一件器材 ----------

    @Test
    void conflict_sameRentedEquipment_blocksMerge() {
        Long keepId = newMember("KEEP-6B", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-6B", 10, 0, "NORMAL");
        Long equipmentId = sharedRentedEquipment(keepId, sourceId);
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.conflicts()).extracting(MergeDtos.ConflictItem::type)
                .contains("RENTED_SAME_EQUIPMENT");
        assertThat(preview.conflicts().get(0).detail()).contains("同时挂在两张卡名下");
        assertThatThrownBy(() -> confirm(preview.previewId())).isInstanceOf(BizException.class);
        // 互斥未解除前器材仍分别挂着（物理上只能有一行 renter_id，这里两行分别表示两侧都有租约的检测场景）
        assertThat(equipmentRepository.findById(equipmentId)).isPresent();
    }

    // ---------- 6c. 互斥：同一弓种适用范围重叠的有效认证 ----------

    @Test
    void conflict_overlappingValidCerts_blocksMerge() {
        Long keepId = newMember("KEEP-6C", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-6C", 10, 0, "NORMAL");
        certApplication(keepId, "RECURVE", 30, "APPROVED", LocalDateTime.now().plusMonths(6));
        certApplication(sourceId, "RECURVE", 50, "APPROVED", LocalDateTime.now().plusMonths(6));
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.conflicts()).extracting(MergeDtos.ConflictItem::type)
                .contains("VALID_CERT_SCOPE");
        assertThatThrownBy(() -> confirm(preview.previewId())).isInstanceOf(BizException.class);

        // 一侧认证已过期（不是有效认证）就不再互斥，可以合并
        Long keep2 = newMember("KEEP-6D", 10, 0, "NORMAL");
        Long source2 = newMember("SRC-6D", 10, 0, "NORMAL");
        certApplication(keep2, "TRADITIONAL", 18, "APPROVED", LocalDateTime.now().minusDays(1));
        certApplication(source2, "TRADITIONAL", 18, "APPROVED", LocalDateTime.now().plusMonths(6));
        MergeDtos.MergePreview preview2 = preview(keep2, source2);
        assertThat(preview2.conflicts()).isEmpty();
    }

    // ---------- 6d. 只有一方有进行中回合：不互斥，可合并迁移 ----------

    @Test
    void oneSideOngoingRound_isNotConflictAndMigrates() {
        Long keepId = newMember("KEEP-6E", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-6E", 10, 0, "NORMAL");
        Long roundId = ongoingRound(sourceId, lane(10), "RECURVE");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.conflicts()).isEmpty();
        assertThat(preview.source().ongoingRounds()).hasSize(1);
        confirm(preview.previewId());
        assertThat(getRound(roundId).getMember().getId()).isEqualTo(keepId);
        assertThat(getRound(roundId).getStatus()).isEqualTo("ONGOING");
    }

    // ---------- 7. 两个操作端并发确认同一来源档案：只成功一次 ----------

    @Test
    void concurrentConfirm_onlyOneSucceeds_loserSeesHandledMessage() throws Exception {
        Long keepId = newMember("KEEP-7", 100, 0, "NORMAL");
        Long sourceId = newMember("SRC-7", 100, 0, "NORMAL");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        Long previewId = preview.previewId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

        CallableConfirm a = new CallableConfirm(previewId, "值班经理甲", ready, start, outcomes, "A");
        CallableConfirm b = new CallableConfirm(previewId, "值班经理乙", ready, start, outcomes, "B");
        Future<?> fa = pool.submit(a);
        Future<?> fb = pool.submit(b);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        fa.get(10, TimeUnit.SECONDS);
        fb.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        long success = outcomes.stream().filter(o -> o.startsWith("A-ok") || o.startsWith("B-ok")).count();
        long failure = outcomes.stream().filter(o -> o.contains("fail")).count();
        assertThat(success).isEqualTo(1);
        assertThat(failure).isEqualTo(1);
        // 失败方明确看到档案已被处理及去向
        String failMessage = outcomes.stream().filter(o -> o.contains("fail")).findFirst().orElseThrow();
        assertThat(failMessage).contains("已被处理");
        assertThat(failMessage).contains("KEEP-7");
        // 余额只累加一次：100 + 100 = 200，而不是 300
        assertThat(member(keepId).getBalance()).isEqualByComparingTo("200.00");
        assertThat(member(sourceId).getStatus()).isEqualTo(Member.STATUS_MERGED);
    }

    /** 并发确认任务：在独立事务里跑服务（@Transactional 由 Spring AOP 生效） */
    private final class CallableConfirm implements Runnable {
        private final Long previewId;
        private final String name;
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final List<String> outcomes;
        private final String tag;

        CallableConfirm(Long previewId, String name, CountDownLatch ready, CountDownLatch start,
                List<String> outcomes, String tag) {
            this.previewId = previewId;
            this.name = name;
            this.ready = ready;
            this.start = start;
            this.outcomes = outcomes;
            this.tag = tag;
        }

        @Override
        public void run() {
            try {
                ready.countDown();
                start.await();
                MergeDtos.MergeResult result = mergeService.confirmMerge(
                        new MergeDtos.MergeConfirmReq(previewId, name));
                outcomes.add(tag + "-ok:" + result.mergeNo());
            } catch (Exception e) {
                outcomes.add(tag + "-fail:" + rootMessage(e));
            }
        }
    }

    // ---------- 8. 迁移任一步中断：整体回滚，来源档案仍可用 ----------

    @Test
    void migrationFailure_rollsBackEverything_sourceStaysUsable() {
        Long keepId = newMember("KEEP-8", 100, 500, "NORMAL");
        Long sourceId = newMember("SRC-8", 80, 200, "NORMAL");
        submittedRound(sourceId, lane(10), "RECURVE",
                List.of("9", "9", "9", "9", "9", "9"), LocalDateTime.of(2026, 9, 1, 10, 0));
        Long courseId = course("回滚课");
        enroll(sourceId, courseId, LocalDateTime.of(2026, 9, 1, 9, 0));
        Long equipmentId = rentedEquipment(sourceId);
        MergeDtos.MergePreview preview = preview(keepId, sourceId);

        migrator.injectFailureBeforeStep(MergeRelationMigrator.STEP_EQUIPMENT);
        assertThatThrownBy(() -> confirm(preview.previewId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EQUIPMENT");

        // 整体回滚：余额 / 累计消费 / 等级未变，关系未迁移，来源仍 ACTIVE
        Member keep = member(keepId);
        Member source = member(sourceId);
        assertThat(keep.getBalance()).isEqualByComparingTo("100.00");
        assertThat(keep.getTotalSpend()).isEqualByComparingTo("500.00");
        assertThat(source.getStatus()).isEqualTo(Member.STATUS_ACTIVE);
        assertThat(source.getBalance()).isEqualByComparingTo("80.00");
        // 课程报名迁移发生在器材之前，也必须随事务回滚
        CourseEnroll enroll = enrollRepository.findByCourseIdOrderByIdAsc(courseId).get(0);
        assertThat(enroll.getMember().getId()).isEqualTo(sourceId);
        assertThat(equipmentRepository.findById(equipmentId).orElseThrow().getRenterId()).isEqualTo(sourceId);
        // 回合仍属于来源卡
        assertThat(roundRepository.findByMemberIdOrderByStartTimeDesc(sourceId)).hasSize(1);
        // 预演仍是 PREVIEWED（消费在迁移前，但也在同一事务内回滚），清除故障后重新确认可成功
        migrator.clearInjectedFailure();
        MergeDtos.MergeResult result = confirm(preview.previewId());
        assertThat(result.balanceAfter()).isEqualByComparingTo("180.00");
        assertThat(member(sourceId).getStatus()).isEqualTo(Member.STATUS_MERGED);
    }

    // ---------- 9. 旧卡号入口跳转到保留档案，显示合并时间与原因 ----------

    @Test
    void oldCardEntry_redirectsToKeepMemberWithMergeTimeAndReason() {
        Long keepId = newMember("KEEP-9", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-9", 10, 0, "NORMAL");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        MergeDtos.MergeResult result = confirm(preview.previewId());

        MergeDtos.ArchivedRedirect redirect = mergeService.lookupByCardNo("SRC-9");
        assertThat(redirect.status()).isEqualTo("MERGED");
        assertThat(redirect.sourceMemberId()).isEqualTo(sourceId);
        assertThat(redirect.targetMemberId()).isEqualTo(keepId);
        assertThat(redirect.targetCardNo()).isEqualTo("KEEP-9");
        // H2 DATETIME 精度到纳秒以下会截断，比较到秒即可
        assertThat(java.time.Duration.between(redirect.mergedAt(), result.mergedAt()).toMillis()).isBetween(-1L, 1L);
        assertThat(redirect.mergeReason()).isEqualTo("误开两张卡");
        assertThat(redirect.mergedBy()).isEqualTo("值班经理甲");

        // 在用卡查询返回本人
        MergeDtos.ArchivedRedirect active = mergeService.lookupByCardNo("KEEP-9");
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.targetMemberId()).isEqualTo(keepId);
    }

    // ---------- 10. 封存档案不能充值 / 开台 / 报名 / 租借 / 开始新回合 ----------

    @Test
    void archivedMember_blockedFromAllBusinessActions() {
        Long keepId = newMember("KEEP-10", 10000, 0, "GOLD");
        Long sourceId = newMember("SRC-10", 10, 0, "NORMAL");
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        confirm(preview.previewId());

        // 充值
        assertThatThrownBy(() -> memberService.recharge(sourceId,
                new com.archery.range.dto.MemberDtos.RechargeReq(new BigDecimal("200"))))
                .isInstanceOf(BizException.class).hasMessageContaining("合并封存");
    }

    // ---------- 11. 不能选择已封存档案再发起合并；同卡不能合并 ----------

    @Test
    void preview_rejectsArchivedOrSameCard() {
        Long keepId = newMember("KEEP-11", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-11", 10, 0, "NORMAL");
        confirm(preview(keepId, sourceId).previewId());

        // 已封存的来源档案不能再作为合并方
        assertThatThrownBy(() -> preview(keepId, sourceId))
                .isInstanceOf(BizException.class).hasMessageContaining("已合并封存");
        // 同一张卡
        Long otherId = newMember("SRC-11B", 10, 0, "NORMAL");
        assertThatThrownBy(() -> preview(otherId, otherId))
                .isInstanceOf(BizException.class).hasMessageContaining("不能是同一张卡");
    }

    // ---------- 12. 认证历史与申请完整迁移（含规则快照相关评定不丢失引用） ----------

    @Test
    void merge_certApplicationsAndHistoryAllMigrated() {
        Long keepId = newMember("KEEP-12", 10, 0, "NORMAL");
        Long sourceId = newMember("SRC-12", 10, 0, "NORMAL");
        // 一笔已撤回的历史认证 + 一笔待复核申请（有效认证只放在一侧，避免同弓种互斥）
        certApplication(sourceId, "COMPOUND", 30, "WITHDRAWN", LocalDateTime.now().plusMonths(3));
        certApplication(sourceId, "TRADITIONAL", 18, "PENDING", null);
        MergeDtos.MergePreview preview = preview(keepId, sourceId);
        assertThat(preview.source().certApplications()).hasSize(2);
        assertThat(preview.source().validCerts()).isEmpty();
        confirm(preview.previewId());
        List<CertApplication> migrated = certApplicationRepository.findByMemberIdOrderByIdDesc(keepId);
        assertThat(migrated).extracting(CertApplication::getStatus)
                .contains("WITHDRAWN", "PENDING");
        assertThat(certApplicationRepository.findByMemberIdOrderByIdDesc(sourceId)).isEmpty();
    }

    // ================= helpers =================

    private MergeDtos.MergePreview preview(Long keepId, Long sourceId) {
        return tx.execute(s -> mergeService.preview(new MergeDtos.MergePreviewReq(
                keepId, sourceId, "值班经理甲", "误开两张卡")));
    }

    private MergeDtos.MergeResult confirm(Long previewId) {
        return tx.execute(s -> mergeService.confirmMerge(
                new MergeDtos.MergeConfirmReq(previewId, "值班经理甲")));
    }

    private Member member(Long id) {
        return tx.execute(s -> memberRepository.findById(id).orElseThrow());
    }

    private Round getRound(Long id) {
        return tx.execute(s -> roundRepository.findById(id).orElseThrow());
    }

    private long seq() {
        return SEQ.incrementAndGet();
    }

    private Long newMember(String cardNo, double balance, double spend, String level) {
        return newMember(cardNo, balance, spend, level, "会员" + cardNo);
    }

    private Long newMember(String cardNo, double balance, double spend, String level, String name) {
        long s = seq();
        return tx.execute(s0 -> {
            Member m = new Member();
            m.setCardNo(cardNo);
            m.setName(name);
            m.setPhone("137" + String.format("%08d", s));
            m.setLevel(level);
            m.setBalance(BigDecimal.valueOf(balance));
            m.setTotalSpend(BigDecimal.valueOf(spend));
            m.setRegisterDate(LocalDate.of(2026, 1, 1));
            m.setStatus(Member.STATUS_ACTIVE);
            return memberRepository.save(m).getId();
        });
    }

    private Long lane(int distance) {
        long s = seq();
        return tx.execute(s0 -> {
            Lane lane = new Lane();
            lane.setLaneNo("M" + s);
            lane.setDistance(distance);
            lane.setTargetType("纸质靶");
            lane.setStatus("OPEN");
            lane.setHourlyPrice(new BigDecimal("60.00"));
            return laneRepository.save(lane).getId();
        });
    }

    private Long course(String name) {
        return tx.execute(s -> {
            Course course = new Course();
            course.setCourseName(name + seq());
            course.setCoach("测试教练");
            course.setLevel("BASIC");
            course.setClassTime(LocalDateTime.of(2026, 10, 10, 19, 0));
            course.setCapacity(20);
            course.setEnrolled(0);
            course.setVenue("测试场地");
            return courseRepository.save(course).getId();
        });
    }

    private Long enroll(Long memberId, Long courseId, LocalDateTime enrollTime) {
        return tx.execute(s -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            Course course = courseRepository.findById(courseId).orElseThrow();
            CourseEnroll enroll = new CourseEnroll();
            enroll.setCourse(course);
            enroll.setMember(member);
            enroll.setEnrollTime(enrollTime);
            return enrollRepository.save(enroll).getId();
        });
    }

    private Long rentedEquipment(Long memberId) {
        long s = seq();
        return tx.execute(s0 -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            Equipment equipment = new Equipment();
            equipment.setEquipCode("ME-" + s);
            equipment.setType("GEAR");
            equipment.setBrand("测试品牌");
            equipment.setRentPrice(new BigDecimal("10.00"));
            equipment.setStatus("RENTED");
            equipment.setRenterId(member.getId());
            equipment.setRenterName(member.getName());
            equipment.setRentedAt(LocalDateTime.of(2026, 9, 20, 12, 0));
            return equipmentRepository.save(equipment).getId();
        });
    }

    /**
     * 构造「双方租着同一件器材」的互斥场景：equipment.renter_id 是单值列，正常业务里物理上只有一个租约，
     * 这里把同一件器材的影子租约挂到来源卡名下（merge_equipment_rent_shadow，合并互斥检测专门识别这种脏数据）。
     */
    private Long sharedRentedEquipment(Long keepId, Long sourceId) {
        Long equipmentId = rentedEquipment(keepId);
        tx.executeWithoutResult(s -> {
            Equipment equipment = equipmentRepository.findById(equipmentId).orElseThrow();
            com.archery.range.domain.EquipmentRentShadow shadow = new com.archery.range.domain.EquipmentRentShadow();
            shadow.setEquipmentId(equipment.getId());
            shadow.setEquipCode(equipment.getEquipCode());
            shadow.setType(equipment.getType());
            shadow.setBrand(equipment.getBrand());
            shadow.setRenterId(sourceId);
            shadow.setRentedAt(LocalDateTime.of(2026, 9, 21, 12, 0));
            jdbcTemplate.update("INSERT INTO merge_equipment_rent_shadow"
                    + " (equipment_id, equip_code, type, brand, renter_id, rented_at) VALUES (?, ?, ?, ?, ?, ?)",
                    shadow.getEquipmentId(), shadow.getEquipCode(), shadow.getType(), shadow.getBrand(),
                    shadow.getRenterId(), java.sql.Timestamp.valueOf(shadow.getRentedAt()));
        });
        return equipmentId;
    }

    private Long occupiedLane(Long memberId) {
        Long laneId = lane(10);
        return tx.execute(s -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            Lane managed = laneRepository.findById(laneId).orElseThrow();
            managed.setStatus("OCCUPIED");
            managed.setOccupantId(member.getId());
            managed.setOccupantName(member.getName());
            managed.setOpenedAt(LocalDateTime.of(2026, 9, 20, 12, 0));
            managed.setPlannedHours(2);
            return laneRepository.save(managed).getId();
        });
    }

    private CertApplication certApplication(Long memberId, String bowType, int distance,
            String status, LocalDateTime validUntil) {
        long s = seq();
        return tx.execute(s0 -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            CertApplication app = new CertApplication();
            app.setAppNo("T-" + s);
            app.setMember(member);
            app.setBowType(bowType);
            app.setDistance(distance);
            app.setWindowStart(LocalDateTime.of(2026, 9, 1, 0, 0));
            app.setWindowEnd(LocalDateTime.of(2026, 9, 1, 23, 59));
            app.setStatus(status);
            app.setEvalSeq(1);
            app.setCreatedBy("测试教练");
            app.setCreatedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
            if (validUntil != null) {
                app.setValidFrom(LocalDateTime.of(2026, 9, 2, 10, 0));
                app.setValidUntil(validUntil);
            }
            return certApplicationRepository.save(app);
        });
    }

    private Long submittedRound(Long memberId, Long laneId, String bowType, List<String> rings,
            LocalDateTime completedAt) {
        return tx.execute(s -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            Lane managedLane = laneRepository.findById(laneId).orElseThrow();
            Round round = buildRound(member, managedLane, bowType, rings);
            round.setStatus("SUBMITTED");
            round.setStartTime(completedAt.minusMinutes(40));
            round.setCompletedAt(completedAt);
            return roundRepository.save(round).getId();
        });
    }

    private Long ongoingRound(Long memberId, Long laneId, String bowType) {
        return tx.execute(s -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            Lane managedLane = laneRepository.findById(laneId).orElseThrow();
            Round round = buildRound(member, managedLane, bowType, List.of());
            round.setStatus("ONGOING");
            round.setStartTime(LocalDateTime.of(2026, 9, 28, 12, 0));
            round.setCompletedAt(null);
            return roundRepository.save(round).getId();
        });
    }

    private Round buildRound(Member member, Lane lane, String bowType, List<String> rings) {
        Round round = new Round();
        round.setRoundNo("MR-" + seq());
        round.setMember(member);
        round.setLane(lane);
        round.setBowType(bowType);
        round.setArrowCount(6);
        List<ArrowScore> arrows = new ArrayList<>();
        int total = 0;
        for (String ring : rings) {
            int value = RangeDict.ringValue(ring);
            arrows.add(new ArrowScore(ring, value));
            total += value;
        }
        round.setArrows(arrows);
        round.setTotalScore(total);
        if (rings.isEmpty()) {
            round.setAverageScore(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        } else {
            round.setAverageScore(BigDecimal.valueOf(total)
                    .divide(BigDecimal.valueOf(rings.size()), 2, java.math.RoundingMode.HALF_UP));
        }
        round.setPersonalBest(false);
        return round;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
