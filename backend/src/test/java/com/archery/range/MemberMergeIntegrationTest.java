package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.archery.range.common.BizException;
import com.archery.range.common.ForbiddenException;
import com.archery.range.domain.Member;
import com.archery.range.dto.MemberMergeDtos;
import com.archery.range.repository.MemberRepository;
import com.archery.range.service.MemberMergeService;
import com.archery.range.service.MemberService;

/**
 * 重复会员档案合并验收测试（H2 MySQL 模式加载真实 schema.sql 种子数据）：
 * 无互斥项合并、课程重复报名去重、余额与等级重算（不降级）、个人最好重新归组、
 * 预演后数据变化拒绝、并发确认只成功一次、迁移中断整体回滚、旧卡入口跳转、封存卡业务封锁、权限。
 *
 * 全部用例使用独立的新建会员与课程，不污染种子数据。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class MemberMergeIntegrationTest {

    @Autowired
    private MemberMergeService mergeService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    /** 本类插入的箭值 id（清理时精确删除，不靠 id 区间猜测） */
    private final java.util.List<Long> createdArrowIds = new java.util.ArrayList<>();

    /**
     * 全部用例共享同一个内存库，结束后按本类前缀清理新建数据（合并单 / 轨迹 / 回合箭支 /
     * 报名 / 认证 / 赛事 / 课程 / 器材还原 / 会员），保证不污染种子数据与其他测试类。
     */
    @AfterEach
    void cleanup() {
        // 本类新建回合号 TR-* 的箭支连接与箭值
        List<Long> roundIds = jdbc.queryForList(
                "SELECT id FROM rounds WHERE round_no LIKE 'TR-%'", Long.class);
        if (!roundIds.isEmpty()) {
            String in = roundIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            jdbc.update("DELETE FROM round_arrow WHERE round_id IN (" + in + ")");
            jdbc.update("DELETE FROM rounds WHERE id IN (" + in + ")");
        }
        if (!createdArrowIds.isEmpty()) {
            String arrowIn = createdArrowIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            jdbc.update("DELETE FROM arrow_score WHERE id IN (" + arrowIn + ")");
        }
        createdArrowIds.clear();
        jdbc.update("DELETE FROM member_merge_log WHERE merge_id IN (SELECT id FROM member_merge"
                + " WHERE keep_member_id IN (SELECT id FROM members WHERE card_no LIKE 'T-%')"
                + " OR source_member_id IN (SELECT id FROM members WHERE card_no LIKE 'T-%'))");
        jdbc.update("DELETE FROM member_merge WHERE keep_member_id IN (SELECT id FROM members WHERE card_no LIKE 'T-%')"
                + " OR source_member_id IN (SELECT id FROM members WHERE card_no LIKE 'T-%')");
        jdbc.update("DELETE FROM course_enroll WHERE course_id IN (SELECT id FROM courses"
                + " WHERE course_name LIKE '合并%' OR course_name LIKE '重复%' OR course_name LIKE '回滚%')");
        jdbc.update("DELETE FROM courses WHERE course_name LIKE '合并%' OR course_name LIKE '重复%'"
                + " OR course_name LIKE '回滚%'");
        jdbc.update("DELETE FROM cert_application WHERE app_no LIKE 'TC-%'");
        jdbc.update("DELETE FROM tournament_team_member WHERE tournament_id IN ("
                + "SELECT id FROM tournament WHERE name LIKE '回滚%')");
        jdbc.update("DELETE FROM tournament_team WHERE tournament_id IN ("
                + "SELECT id FROM tournament WHERE name LIKE '回滚%')");
        jdbc.update("DELETE FROM tournament WHERE name LIKE '回滚%'");
        jdbc.update("DELETE FROM equipment WHERE equip_code LIKE 'T-DUP-%'");
        // 器材 3 / 11 若被用例留在租出态，还原在库
        jdbc.update("UPDATE equipment SET status = 'INSTOCK', renter_id = NULL, renter_name = NULL, rented_at = NULL"
                + " WHERE id IN (3, 11) AND status = 'RENTED'");
        jdbc.update("DELETE FROM members WHERE card_no LIKE 'T-%'");
    }

    private static final String OP = "值班经理甲";

    private LocalDateTime ts(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private Long newMember(String cardNo, String name, String balance, String spend, String level) {
        jdbc.update("INSERT INTO members (card_no, name, phone, level, balance, register_date, total_spend, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                cardNo, name, "139" + String.format("%08d", Math.abs(cardNo.hashCode()) % 100000000),
                level, new BigDecimal(balance), LocalDate.of(2025, 1, 1), new BigDecimal(spend));
        return jdbc.queryForObject("SELECT id FROM members WHERE card_no = ?", Long.class, cardNo);
    }

    private Long newCourse(String name, String classTime) {
        jdbc.update("INSERT INTO courses (course_name, coach, level, class_time, capacity, enrolled, venue)"
                + " VALUES (?, '张岩', 'BASIC', ?, 20, 0, '一号教学区')", name, ts(classTime));
        return jdbc.queryForObject("SELECT id FROM courses WHERE course_name = ?", Long.class, name);
    }

    private void enroll(Long courseId, Long memberId, String time) {
        jdbc.update("INSERT INTO course_enroll (course_id, member_id, enroll_time) VALUES (?, ?, ?)",
                courseId, memberId, ts(time));
    }

    /** 插入一个已提交回合（含 6 支箭，按 shot_index 0..5） */
    private Long submittedRound(Long memberId, long laneId, String roundNo, String bowType,
            String startTime, List<String> rings) {
        int total = rings.stream().mapToInt(ringValue ->
                "X".equals(ringValue) || "10".equals(ringValue) ? 10 : Integer.parseInt(ringValue)).sum();
        BigDecimal avg = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(rings.size()), 2,
                java.math.RoundingMode.HALF_UP);
        jdbc.update("INSERT INTO rounds (round_no, member_id, lane_id, bow_type, start_time, completed_at,"
                + " arrow_count, total_score, average_score, personal_best, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'SUBMITTED')",
                roundNo, memberId, laneId, bowType, ts(startTime), ts(startTime),
                rings.size(), total, avg);
        Long roundId = jdbc.queryForObject("SELECT id FROM rounds WHERE round_no = ?", Long.class, roundNo);
        for (int i = 0; i < rings.size(); i += 1) {
            String ring = rings.get(i);
            int value = "X".equals(ring) || "10".equals(ring) ? 10 : Integer.parseInt(ring);
            jdbc.update("INSERT INTO arrow_score (ring, ring_value) VALUES (?, ?)", ring, value);
            Long arrowId = jdbc.queryForObject("SELECT id FROM arrow_score ORDER BY id DESC LIMIT 1", Long.class);
            createdArrowIds.add(arrowId);
            jdbc.update("INSERT INTO round_arrow (round_id, arrow_id, shot_index) VALUES (?, ?, ?)",
                    roundId, arrowId, i);
        }
        return roundId;
    }

    private void ongoingRound(Long memberId, long laneId, String roundNo) {
        jdbc.update("INSERT INTO rounds (round_no, member_id, lane_id, bow_type, start_time, completed_at,"
                + " arrow_count, total_score, average_score, personal_best, status)"
                + " VALUES (?, ?, ?, 'RECURVE', ?, NULL, 6, 0, 0.00, 0, 'ONGOING')",
                roundNo, memberId, laneId, ts("2026-09-28 10:00:00"));
    }

    private void rent(Long equipmentId, Long memberId, String name) {
        jdbc.update("UPDATE equipment SET status = 'RENTED', renter_id = ?, renter_name = ?, rented_at = ?"
                + " WHERE id = ?", memberId, name, ts("2026-09-28 09:00:00"), equipmentId);
    }

    /** 一笔当前有效的已通过认证（规则快照列不做外键，造测试数据时只写主表） */
    private void validCert(Long memberId, String appNo, String bowType, int distance, String validUntil) {
        jdbc.update("INSERT INTO cert_application (app_no, member_id, bow_type, distance, window_start, window_end,"
                + " status, eval_seq, reviewed_by, reviewed_at, valid_from, valid_until, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', 1, '教练', ?, ?, ?, '教练', ?)",
                appNo, memberId, bowType, distance,
                ts("2026-09-01 00:00:00"), ts("2026-09-01 23:59:59"),
                ts("2026-09-02 10:00:00"), ts("2026-09-02 10:00:00"), ts(validUntil), ts("2026-09-02 09:00:00"));
    }

    private MemberMergeDtos.MergePreviewReq req(Long keep, Long source, String reason) {
        return new MemberMergeDtos.MergePreviewReq(keep, source, reason, OP, "MANAGER");
    }

    private BigDecimal balance(Long memberId) {
        return jdbc.queryForObject("SELECT balance FROM members WHERE id = ?", BigDecimal.class, memberId);
    }

    private String level(Long memberId) {
        return jdbc.queryForObject("SELECT level FROM members WHERE id = ?", String.class, memberId);
    }

    private String status(Long memberId) {
        return jdbc.queryForObject("SELECT status FROM members WHERE id = ?", String.class, memberId);
    }

    private long countSql(String sql, Object... args) {
        Long c = jdbc.queryForObject(sql, Long.class, args);
        return c == null ? 0 : c;
    }

    // ---------- 1. 无互斥项完整合并：余额 / 累计消费 / 等级 / 报名 / 回合 / 器材 / 认证 ----------

    @Test
    void happyPath_fullMerge() {
        Long keep = newMember("T-001", "合并甲", "100.00", "2000.00", "NORMAL");
        Long source = newMember("T-002", "合并甲", "300.00", "1500.00", "NORMAL");
        Long c1 = newCourse("合并测试课1", "2026-10-10 19:00:00");
        Long c2 = newCourse("合并测试课2", "2026-10-11 19:00:00");
        enroll(c1, keep, "2026-09-01 09:00:00");
        enroll(c2, source, "2026-09-02 09:00:00");
        submittedRound(keep, 1L, "TR-H1", "RECURVE", "2026-09-10 19:00:00",
                List.of("9", "9", "8", "8", "7", "7"));
        submittedRound(source, 1L, "TR-H2", "RECURVE", "2026-09-11 19:00:00",
                List.of("10", "10", "9", "9", "8", "8"));
        rent(11L, source, "合并甲"); // E-AR-01 在库箭支
        validCert(keep, "TC-001", "RECURVE", 50, "2027-01-01 10:00:00");

        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "同一人误开两卡")));
        assertThat(preview.canMerge()).isTrue();
        assertThat(preview.conflicts()).isEmpty();
        assertThat(preview.plan().balanceAfter()).isEqualByComparingTo("400.00");
        assertThat(preview.plan().spendAfter()).isEqualByComparingTo("3500.00");
        // 累计消费 3500 重算到银卡，且不降级
        assertThat(preview.plan().levelAfter()).isEqualTo("SILVER");

        MemberMergeDtos.MergeDetailView done = tx.execute(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        assertThat(done.summary().status()).isEqualTo("CONFIRMED");

        // 余额与累计消费合并到保留卡；来源卡封存并清零
        assertThat(balance(keep)).isEqualByComparingTo("400.00");
        assertThat(jdbc.queryForObject("SELECT total_spend FROM members WHERE id = ?", BigDecimal.class, keep))
                .isEqualByComparingTo("3500.00");
        assertThat(level(keep)).isEqualTo("SILVER");
        assertThat(status(source)).isEqualTo("MERGED");
        assertThat(balance(source)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM members WHERE id = ?", Long.class, source))
                .isEqualTo(keep);
        assertThat(jdbc.queryForObject("SELECT merged_by FROM members WHERE id = ?", String.class, source))
                .isEqualTo(OP);
        // 原卡号 / 原姓名 / 手机号仍保留可查
        assertThat(jdbc.queryForObject("SELECT name FROM members WHERE id = ?", String.class, source))
                .isEqualTo("合并甲");
        assertThat(jdbc.queryForObject("SELECT card_no FROM members WHERE id = ?", String.class, source))
                .isEqualTo("T-002");

        // 业务关系全部迁到保留卡
        assertThat(countSql("SELECT COUNT(*) FROM rounds WHERE member_id = ?", source)).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM rounds WHERE member_id = ?", keep)).isEqualTo(2);
        assertThat(countSql("SELECT COUNT(*) FROM course_enroll WHERE member_id = ?", source)).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM course_enroll WHERE member_id = ?", keep)).isEqualTo(2);
        assertThat(countSql("SELECT COUNT(*) FROM cert_application WHERE member_id = ?", source)).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM cert_application WHERE member_id = ?", keep)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT renter_id FROM equipment WHERE id = 11", Long.class)).isEqualTo(keep);
        // 箭支明细原样保留（6+6=12 支）
        assertThat(countSql("SELECT COUNT(*) FROM round_arrow ra JOIN rounds r ON ra.round_id = r.id"
                + " WHERE r.member_id = ?", keep)).isEqualTo(12);
        // 认证申请（含认证历史：状态 / 有效期 / 复核快照字段）原样迁到保留卡
        assertThat(countSql("SELECT COUNT(*) FROM cert_application WHERE member_id = ? AND app_no = 'TC-001'",
                keep)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM cert_application WHERE app_no = 'TC-001'",
                String.class)).isEqualTo("APPROVED");
    }

    // ---------- 2. 同一课程重复报名只保留较早一条 ----------

    @Test
    void duplicateEnrollment_keepsEarlier() {
        Long keep = newMember("T-011", "报名甲", "0.00", "0.00", "NORMAL");
        Long source = newMember("T-012", "报名甲", "0.00", "0.00", "NORMAL");
        Long c = newCourse("重复报名课", "2026-10-20 19:00:00");
        // 保留卡报得早
        enroll(c, keep, "2026-09-01 08:00:00");
        enroll(c, source, "2026-09-05 08:00:00");

        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "重复报名测试")));
        assertThat(preview.canMerge()).isTrue();
        assertThat(preview.plan().duplicateEnrollments()).hasSize(1);
        assertThat(preview.plan().duplicateEnrollments().get(0).keptCardNo()).isEqualTo("T-011");
        assertThat(preview.plan().enrollmentsToMigrate()).isEmpty();

        tx.executeWithoutResult(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        assertThat(countSql("SELECT COUNT(*) FROM course_enroll WHERE course_id = ? AND member_id = ?", c, keep))
                .isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM course_enroll WHERE member_id = ?", source)).isZero();
        // 较早一条的报名时间保留
        assertThat(jdbc.queryForObject("SELECT enroll_time FROM course_enroll WHERE course_id = ? AND member_id = ?",
                LocalDateTime.class, c, keep)).isEqualTo(ts("2026-09-01 08:00:00"));
    }

    // ---------- 3. 等级按累计消费重算且不能降级 ----------

    @Test
    void levelRecalculatedButNeverDowngraded() {
        // 保留卡已是金卡但合并后累计消费只有 2500（按消费应是普通）→ 不能降级，仍是金卡
        Long keep = newMember("T-021", "等级甲", "100.00", "10500.00", "GOLD");
        Long source = newMember("T-022", "等级甲", "50.00", "100.00", "NORMAL");
        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "不降级测试")));
        assertThat(preview.plan().levelAfter()).isEqualTo("GOLD");
        assertThat(preview.plan().levelDowngraded()).isFalse();
        tx.executeWithoutResult(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        assertThat(level(keep)).isEqualTo("GOLD");

        // 合并后累计消费跨银卡门槛：普通 → 银
        Long k2 = newMember("T-023", "升级乙", "0.00", "2000.00", "NORMAL");
        Long s2 = newMember("T-024", "升级乙", "0.00", "1200.00", "NORMAL");
        MemberMergeDtos.MergePreviewView p2 = tx.execute(s -> mergeService.preview(req(k2, s2, "升级测试")));
        assertThat(p2.plan().levelAfter()).isEqualTo("SILVER");
        tx.executeWithoutResult(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(p2.mergeId(), OP, "MANAGER")));
        assertThat(level(k2)).isEqualTo("SILVER");
    }

    // ---------- 4. 个人最好迁移后按保留会员重新归组 ----------

    @Test
    void personalBestRegroupedAfterMerge() {
        Long keep = newMember("T-031", "PB甲", "0.00", "0.00", "NORMAL");
        Long source = newMember("T-032", "PB甲", "0.00", "0.00", "NORMAL");
        // 同可比组：反曲弓 10 米 6 支。保留卡 48 分带 PB；来源卡 56 分更高（在来源卡也是 PB）
        submittedRound(keep, 1L, "TR-PB1", "RECURVE", "2026-09-10 19:00:00",
                List.of("8", "8", "8", "8", "8", "8"));
        submittedRound(source, 1L, "TR-PB2", "RECURVE", "2026-09-11 19:00:00",
                List.of("10", "10", "10", "9", "9", "8"));
        jdbc.update("UPDATE rounds SET personal_best = 1 WHERE round_no IN ('TR-PB1', 'TR-PB2')");

        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "PB归组测试")));
        MemberMergeDtos.PbGroupResult group = preview.plan().personalBestGroups().stream()
                .filter(g -> g.groupLabel().contains("10 米")).findFirst().orElseThrow();
        assertThat(group.winnerRoundNo()).isEqualTo("TR-PB2");
        assertThat(group.winnerSide()).isEqualTo("SOURCE");

        tx.executeWithoutResult(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        // 全组只有来源卡那条高分保持 PB；原保留卡 PB 标记被清
        assertThat(countSql("SELECT COUNT(*) FROM rounds WHERE round_no = 'TR-PB2' AND personal_best = 1 AND member_id = ?",
                keep)).isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM rounds WHERE round_no = 'TR-PB1' AND personal_best = 1")).isZero();
        // 箭值 / 完成时间未被改写
        assertThat(jdbc.queryForObject("SELECT total_score FROM rounds WHERE round_no = 'TR-PB1'", Integer.class))
                .isEqualTo(48);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM round_arrow ra JOIN rounds r ON ra.round_id = r.id"
                + " WHERE r.round_no = 'TR-PB1'", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM rounds WHERE round_no = 'TR-PB2'",
                LocalDateTime.class)).isEqualTo(ts("2026-09-11 19:00:00"));
    }

    // ---------- 5. 互斥项：双方都有进行中回合 ----------

    @Test
    void conflict_bothOngoingRounds_blocked() {
        Long keep = newMember("T-041", "互斥甲", "0.00", "0.00", "NORMAL");
        Long source = newMember("T-042", "互斥甲", "0.00", "0.00", "NORMAL");
        ongoingRound(keep, 1L, "TR-O1");
        ongoingRound(source, 3L, "TR-O2");
        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "互斥回合")));
        assertThat(preview.canMerge()).isFalse();
        assertThat(preview.conflicts()).extracting(MemberMergeDtos.ConflictItem::type)
                .contains("ONGOING_ROUND");
        boolean blocked = false;
        String why = "";
        try {
            tx.executeWithoutResult(s ->
                    mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        } catch (Exception e) {
            blocked = true;
            why = rootMessage(e);
        }
        assertThat(blocked).isTrue();
        assertThat(why).contains("互斥");
        // 未合并：来源卡仍可用
        assertThat(status(source)).isEqualTo("ACTIVE");
        assertThat(countSql("SELECT COUNT(*) FROM rounds WHERE round_no = 'TR-O2' AND member_id = ?", source))
                .isEqualTo(1);
    }

    // ---------- 6. 互斥项：同一件器材同时租出 ----------

    @Test
    void conflict_sameRentedEquipment_blocked() {
        Long keep = newMember("T-051", "器材甲", "0.00", "0.00", "NORMAL");
        Long source = newMember("T-052", "器材甲", "0.00", "0.00", "NORMAL");
        // 正常流程下一件器材只有一个租用人，两张卡的租借列表不可能同时含同一 equipmentId；
        // 该交集只可能由历史脏数据产生。直接用两张卡的侧视图（同一器材 id 同时在两侧租借列表）
        // 验证互斥识别，确保系统不会自行挑一条覆盖。
        MemberMergeDtos.MergeSide keepSide = new MemberMergeDtos.MergeSide(
                keep, "T-051", "器材甲", "1", "NORMAL", "普通", BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2025, 1, 1), "ACTIVE",
                List.of(), List.of(), List.of(),
                List.of(new MemberMergeDtos.RentalItem(3L, "E-RC-03", "RECURVE", "反曲弓", "KAP",
                        ts("2026-09-28 09:00:00"))),
                List.of(), List.of(), List.of());
        MemberMergeDtos.MergeSide sourceSide = new MemberMergeDtos.MergeSide(
                source, "T-052", "器材甲", "2", "NORMAL", "普通", BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2025, 1, 1), "ACTIVE",
                List.of(), List.of(), List.of(),
                List.of(new MemberMergeDtos.RentalItem(3L, "E-RC-03", "RECURVE", "反曲弓", "KAP",
                        ts("2026-09-28 09:05:00"))),
                List.of(), List.of(), List.of());
        List<MemberMergeDtos.ConflictItem> conflicts =
                com.archery.range.service.MemberMergeService.detectConflictsStatic(keepSide, sourceSide);
        assertThat(conflicts).extracting(MemberMergeDtos.ConflictItem::type).contains("SAME_EQUIPMENT");
        assertThat(conflicts.stream().filter(c -> "SAME_EQUIPMENT".equals(c.type())).findFirst().orElseThrow()
                .target()).contains("E-RC-03");

        // 端到端防御：confirm 阶段若器材行与预演归属矛盾（归还后被保留卡租走），迁移中断回滚
        rent(3L, source, "器材甲");
        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "器材互斥")));
        jdbc.update("UPDATE equipment SET status = 'INSTOCK', renter_id = NULL, renter_name = NULL, rented_at = NULL"
                + " WHERE id = 3");
        rent(3L, keep, "器材甲");
        boolean failed = false;
        try {
            tx.executeWithoutResult(s ->
                    mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        } catch (Exception e) {
            failed = true;
        }
        assertThat(failed).isTrue();
        jdbc.update("UPDATE equipment SET status = 'INSTOCK', renter_id = NULL, renter_name = NULL, rented_at = NULL"
                + " WHERE id = 3");
    }

    // ---------- 7. 互斥项：同一弓种且适用范围重叠的有效认证 ----------

    @Test
    void conflict_overlappingValidCert_blocked() {
        Long keep = newMember("T-061", "认证甲", "0.00", "0.00", "NORMAL");
        Long source = newMember("T-062", "认证甲", "0.00", "0.00", "NORMAL");
        validCert(keep, "TC-061", "RECURVE", 50, "2027-01-01 10:00:00");
        validCert(source, "TC-062", "RECURVE", 30, "2027-01-01 10:00:00");
        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "认证互斥")));
        assertThat(preview.canMerge()).isFalse();
        assertThat(preview.conflicts()).extracting(MemberMergeDtos.ConflictItem::type)
                .contains("VALID_CERT_SCOPE");

        // 不同弓种的有效认证不互斥，可以合并
        Long k2 = newMember("T-063", "认证乙", "0.00", "0.00", "NORMAL");
        Long s2 = newMember("T-064", "认证乙", "0.00", "0.00", "NORMAL");
        validCert(k2, "TC-063", "RECURVE", 50, "2027-01-01 10:00:00");
        validCert(s2, "TC-064", "COMPOUND", 30, "2027-01-01 10:00:00");
        MemberMergeDtos.MergePreviewView p2 = tx.execute(s -> mergeService.preview(req(k2, s2, "不同弓种")));
        assertThat(p2.canMerge()).isTrue();
    }

    // ---------- 8. 预演后数据变化：确认被拒绝并要求重新预演 ----------

    @Test
    void changedAfterPreview_confirmRejected() {
        Long keep = newMember("T-071", "变化甲", "100.00", "0.00", "NORMAL");
        Long source = newMember("T-072", "变化甲", "100.00", "0.00", "NORMAL");
        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "预演变化测试")));

        // 预演后来一笔充值（余额变化）。确认应被拒绝：在独立事务中执行并捕获业务异常，
        // STALE 标记由服务端 REQUIRES_NEW 事务独立提交，不受外层回滚影响。
        tx.executeWithoutResult(s -> memberService.recharge(source,
                new com.archery.range.dto.MemberDtos.RechargeReq(new BigDecimal("200"))));

        String message;
        try {
            tx.executeWithoutResult(s ->
                    mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
            throw new AssertionError("预期因预演后数据变化被拒绝");
        } catch (BizException e) {
            message = e.getMessage();
        }
        assertThat(message).contains("预演之后数据已发生变化").contains("余额");
        // 预演单置 STALE（独立事务已落库），来源卡仍可用
        assertThat(jdbc.queryForObject("SELECT status FROM member_merge WHERE id = ?", String.class,
                preview.mergeId())).isEqualTo("STALE");
        assertThat(status(source)).isEqualTo("ACTIVE");
        assertThat(balance(source)).isEqualByComparingTo("300.00");

        // 重新预演后可以正常确认
        MemberMergeDtos.MergePreviewView preview2 = tx.execute(s -> mergeService.preview(req(keep, source, "重新预演")));
        assertThat(preview2.plan().balanceAfter()).isEqualByComparingTo("400.00");
        tx.executeWithoutResult(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview2.mergeId(), OP, "MANAGER")));
        assertThat(status(source)).isEqualTo("MERGED");
        // 旧预演保留 STALE（预演后数据变化的失效历史，不会被新预演覆盖），轨迹完整可查
        assertThat(jdbc.queryForObject("SELECT status FROM member_merge WHERE id = ?", String.class,
                preview.mergeId())).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT status FROM member_merge WHERE id = ?", String.class,
                preview2.mergeId())).isEqualTo("CONFIRMED");
    }

    // ---------- 9. 并发确认同一来源：只能成功一次，失败方看到已被处理 ----------

    @Test
    void concurrentConfirm_onlyOneSucceeds() throws Exception {
        Long keep = newMember("T-081", "并发甲", "100.00", "0.00", "NORMAL");
        Long source = newMember("T-082", "并发甲", "100.00", "0.00", "NORMAL");
        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "并发确认测试")));
        Long mergeId = preview.mergeId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<String> a = new AtomicReference<>();
        AtomicReference<String> b = new AtomicReference<>();

        Runnable taskA = () -> {
            try {
                tx.executeWithoutResult(s -> {
                    try {
                        ready.countDown();
                        start.await();
                        mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(mergeId, OP, "MANAGER"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
                a.set("ok");
            } catch (Exception e) {
                a.set("fail:" + rootMessage(e));
            }
        };
        Runnable taskB = () -> {
            try {
                tx.executeWithoutResult(s -> {
                    try {
                        ready.countDown();
                        start.await();
                        mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(mergeId, "值班经理乙", "MANAGER"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
                b.set("ok");
            } catch (Exception e) {
                b.set("fail:" + rootMessage(e));
            }
        };

        Future<?> fa = pool.submit(taskA);
        Future<?> fb = pool.submit(taskB);
        ready.await();
        start.countDown();
        fa.get();
        fb.get();
        pool.shutdown();

        boolean oneOk = ("ok".equals(a.get()) && b.get().startsWith("fail"))
                || ("ok".equals(b.get()) && a.get().startsWith("fail"));
        assertThat(oneOk).as("a=%s b=%s", a.get(), b.get()).isTrue();
        String loser = "ok".equals(a.get()) ? b.get() : a.get();
        assertThat(loser).contains("已被");
        // 余额只累加一次：200，不是 300
        assertThat(balance(keep)).isEqualByComparingTo("200.00");
        assertThat(status(source)).isEqualTo("MERGED");
        assertThat(countSql("SELECT COUNT(*) FROM member_merge WHERE source_member_id = ? AND status = 'CONFIRMED'",
                source)).isEqualTo(1);
    }

    // ---------- 10. 迁移中断整体回滚：来源档案仍保持可用 ----------

    @Test
    void migrationFailure_fullyRolledBack() {
        Long keep = newMember("T-091", "回滚甲", "0.00", "0.00", "NORMAL");
        Long source = newMember("T-092", "回滚甲", "0.00", "0.00", "NORMAL");
        // 制造赛事唯一键冲突：同一赛事里两张卡分别在不同队，合并迁移会撞 (tournament_id, member_id)
        jdbc.update("INSERT INTO tournament (name, team_size, ends_per_match, arrows_per_end, status,"
                + " created_by, created_at) VALUES ('回滚测试赛事', 4, 1, 1, 'DRAFT', '经理', ?)",
                ts("2026-09-28 12:00:00"));
        Long tid = jdbc.queryForObject("SELECT id FROM tournament WHERE name = '回滚测试赛事'", Long.class);
        jdbc.update("INSERT INTO tournament_team (tournament_id, team_name, created_at) VALUES (?, '回滚队A', ?)",
                tid, ts("2026-09-28 12:00:00"));
        Long teamA = jdbc.queryForObject("SELECT id FROM tournament_team WHERE tournament_id = ? ORDER BY id LIMIT 1",
                Long.class, tid);
        jdbc.update("INSERT INTO tournament_team (tournament_id, team_name, created_at) VALUES (?, '回滚队B', ?)",
                tid, ts("2026-09-28 12:00:00"));
        Long teamB = jdbc.queryForObject("SELECT id FROM tournament_team WHERE tournament_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, tid);
        // 每队只插一条该会员的站位（另两条站位留空不影响唯一键冲突复现）
        jdbc.update("INSERT INTO tournament_team_member (team_id, tournament_id, member_id, position)"
                + " VALUES (?, ?, ?, 0)", teamA, tid, keep);
        jdbc.update("INSERT INTO tournament_team_member (team_id, tournament_id, member_id, position)"
                + " VALUES (?, ?, ?, 0)", teamB, tid, source);
        enroll(newCourse("回滚测试课", "2026-11-01 19:00:00"), source, "2026-09-01 09:00:00");

        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "回滚测试")));
        assertThat(preview.canMerge()).isTrue();

        // 迁移赛事队员身份时撞 (tournament_id, member_id) 唯一键：任何一步失败都必须整体回滚
        boolean failed = false;
        try {
            tx.executeWithoutResult(s ->
                    mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));
        } catch (Exception e) {
            failed = true;
        }
        assertThat(failed).isTrue();

        // 整体回滚：来源卡仍 ACTIVE、余额不变、报名未迁、合并单未确认
        assertThat(status(source)).isEqualTo("ACTIVE");
        assertThat(status(keep)).isEqualTo("ACTIVE");
        assertThat(balance(source)).isEqualByComparingTo("0.00");
        assertThat(countSql("SELECT COUNT(*) FROM course_enroll WHERE member_id = ?", source)).isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM tournament_team_member WHERE member_id = ?", source)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM member_merge WHERE id = ?", String.class,
                preview.mergeId())).isEqualTo("PREVIEWED");
    }

    // ---------- 11. 旧卡号入口跳转 + 封存后业务封锁 ----------

    @Test
    void sealedCard_redirectAndBusinessBlocked() {
        Long keep = newMember("T-101", "跳转甲", "100.00", "0.00", "NORMAL");
        Long source = newMember("T-102", "跳转甲", "100.00", "0.00", "NORMAL");

        // 正常卡入口
        MemberMergeDtos.CardEntryView open = tx.execute(s -> mergeService.entryByCardNo("T-101"));
        assertThat(open.merged()).isFalse();

        MemberMergeDtos.MergePreviewView preview = tx.execute(s -> mergeService.preview(req(keep, source, "旧卡跳转测试")));
        tx.executeWithoutResult(s ->
                mergeService.confirm(new MemberMergeDtos.MergeConfirmReq(preview.mergeId(), OP, "MANAGER")));

        // 旧卡号进入：返回保留档案跳转信息（合并时间 / 经理 / 原因）
        MemberMergeDtos.CardEntryView old = tx.execute(s -> mergeService.entryByCardNo("T-102"));
        assertThat(old.merged()).isTrue();
        assertThat(old.redirectMemberId()).isEqualTo(keep);
        assertThat(old.redirectCardNo()).isEqualTo("T-101");
        assertThat(old.mergedBy()).isEqualTo(OP);
        assertThat(old.mergeReason()).isEqualTo("旧卡跳转测试");
        assertThat(old.mergedAt()).isNotNull();

        // 封存卡不能充值 / 开台 / 报名 / 租借 / 开始新回合
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                memberService.recharge(source, new com.archery.range.dto.MemberDtos.RechargeReq(new BigDecimal("100")))))
                .isInstanceOf(BizException.class).hasMessageContaining("封存");

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                memberService.requireActive(source)))
                .isInstanceOf(BizException.class).hasMessageContaining("封存");

        // 普通会员只读：自己能查合并结果，不能发起合并
        MemberMergeDtos.MergePreviewReq memberReq = new MemberMergeDtos.MergePreviewReq(
                keep, source, "x", "普通会员张三", "MEMBER");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> mergeService.preview(memberReq)))
                .isInstanceOf(ForbiddenException.class);

        // 合并轨迹：保留卡与来源卡都能查到
        List<MemberMergeDtos.MergeSummary> keepTrail = tx.execute(s -> mergeService.listByMember(keep));
        List<MemberMergeDtos.MergeSummary> sourceTrail = tx.execute(s -> mergeService.listByMember(source));
        assertThat(keepTrail).isNotEmpty();
        assertThat(sourceTrail).isNotEmpty();
        MemberMergeDtos.MergeDetailView detail = tx.execute(s -> mergeService.detail(preview.mergeId()));
        assertThat(detail.logs()).extracting(MemberMergeDtos.MergeLogView::action)
                .contains("PREVIEW", "CONFIRM");
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
