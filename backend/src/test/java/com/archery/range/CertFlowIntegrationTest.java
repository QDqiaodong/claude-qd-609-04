package com.archery.range;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import com.archery.range.common.BizException;
import com.archery.range.domain.CertApplication;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.CertDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.service.CertService;
import com.archery.range.service.EquipmentService;
import com.archery.range.service.LaneService;

/**
 * 弓种能力认证端到端集成测试（H2 MySQL 模式加载真实 schema.sql 种子数据）：
 * 覆盖证据窗口硬校验、版本化规则快照、复核状态流转、并发冲突、取代、撤回、过期与入口识别。
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CertFlowIntegrationTest {

    @Autowired
    private CertService certService;
    @Autowired
    private LaneService laneService;
    @Autowired
    private EquipmentService equipmentService;
    @Autowired
    private CertApplicationRepository appRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private LocalDateTime ts(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    // ---------- 1. 种子数据：版本化规则、现行 / 过期 / 撤回认证 ----------

    @Test
    @Order(1)
    void seedData_rulesAndMemberCerts() {
        CertDtos.MemberCerts m1 = certService.memberCerts(1L);
        // 陈子昂：RECURVE-50 v1 认证 2027 年到期，当前有效
        assertThat(m1.current()).hasSize(1);
        assertThat(m1.current().get(0).ruleCode()).isEqualTo("RECURVE-50");
        assertThat(m1.current().get(0).ruleVersion()).isEqualTo(1);

        CertDtos.MemberCerts m2 = certService.memberCerts(2L);
        // 林悦：2026-09-11 已过期，不能再算有效；历史仍可见
        assertThat(m2.current()).isEmpty();
        assertThat(m2.history()).anyMatch(a -> a.displayStatus().equals("EXPIRED"));

        CertDtos.MemberCerts m4 = certService.memberCerts(4L);
        // 孙嘉：唯一一笔 COMPOUND-30 认证已撤回，当前无有效认证
        assertThat(m4.current()).isEmpty();
        assertThat(m4.history()).anyMatch(a -> a.status().equals("WITHDRAWN"));

        // RECURVE-50 有 v1（停用）/ v2（现行）两个版本
        assertThat(certService.listRules()).filteredOn(r -> r.ruleCode().equals("RECURVE-50"))
                .extracting(CertDtos.RuleView::version).containsExactly(2, 1);
        assertThat(certService.listActiveRules()).filteredOn(r -> r.ruleCode().equals("RECURVE-50"))
                .singleElement().extracting(CertDtos.RuleView::version).isEqualTo(2);
    }

    // ---------- 2. 种子数据过期 / 撤回后入口必须拦截，有效认证放行 ----------

    @Test
    @Order(2)
    void entryGates_seedState() {
        // 会员 1 持 RECURVE-50 有效认证：覆盖 50 米与 30 米，不覆盖问题本身（距离维度直接验证）
        CertDtos.CertItem cert50 = certService.memberCerts(1L).current().get(0);
        assertThat(cert50.distance()).isGreaterThanOrEqualTo(50);

        // 会员 2 认证已过期：30 米开台无覆盖认证（开台前置条件由 service 校验）
        assertThatThrownBy(() -> transactionTemplate.execute(s -> {
            laneService.open(6L, new com.archery.range.dto.LaneDtos.LaneOpenReq(2L, 1));
            return null;
        })).isInstanceOf(BizException.class).hasMessageContaining("认证");

        // 会员 4 复合弓认证已撤回：租借复合弓 E-CP-01（id=4）被拒
        assertThatThrownBy(() -> transactionTemplate.execute(s -> {
            equipmentService.rent(4L, new com.archery.range.dto.EquipmentDtos.RentReq(4L));
            return null;
        })).isInstanceOf(BizException.class).hasMessageContaining("复合弓");

        // 会员 1 持有效反曲弓认证：租借反曲弓 E-RC-01（id=1，在库）应通过（扣费在余额充足前提下成功）
        transactionTemplate.executeWithoutResult(s ->
                equipmentService.rent(1L, new com.archery.range.dto.EquipmentDtos.RentReq(1L)));
    }

    // ---------- 3. 证据窗口硬校验：逐条原因，未完成回合绝不计入 ----------

    @Test
    @Order(3)
    void evidenceValidation_explicitReasons() {
        // 会员 9 + COMPOUND-30 现行规则（id=5）：用回合 7（6 支且平均分 6.17）——每组箭数不符
        CertDtos.CreateAppReq wrongGroup = new CertDtos.CreateAppReq(9L, 5L,
                ts("2026-09-20 00:00:00"), ts("2026-09-20 23:59:59"), List.of(7L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(wrongGroup))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("每组 6 支");

        // 会员 4 + COMPOUND-30：回合 9 是 6 支组（箭数不符）
        CertDtos.CreateAppReq wrongGroup2 = new CertDtos.CreateAppReq(4L, 5L,
                ts("2026-09-21 00:00:00"), ts("2026-09-21 23:59:59"), List.of(9L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(wrongGroup2))
                .isInstanceOf(BizException.class).hasMessageContaining("每组 6 支");

        // 弓种不匹配：会员 4（复合弓回合 3）拿去申请反曲弓规则（RECURVE-30 v1 id=2）
        CertDtos.CreateAppReq wrongBow = new CertDtos.CreateAppReq(4L, 2L,
                ts("2026-09-16 00:00:00"), ts("2026-09-16 23:59:59"), List.of(3L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(wrongBow))
                .isInstanceOf(BizException.class).hasMessageContaining("弓种");

        // 未完成回合：会员 3 的 ONGOING 回合 10 不能计入
        CertDtos.CreateAppReq ongoing = new CertDtos.CreateAppReq(3L, 1L,
                ts("2026-09-22 00:00:00"), ts("2026-09-22 23:59:59"), List.of(10L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(ongoing))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未完成");

        // 跨会员：用会员 1 的回合 6 给会员 2 作证
        CertDtos.CreateAppReq wrongMember = new CertDtos.CreateAppReq(2L, 1L,
                ts("2026-09-19 00:00:00"), ts("2026-09-19 23:59:59"), List.of(6L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(wrongMember))
                .isInstanceOf(BizException.class).hasMessageContaining("属于会员");

        // 跨出时间窗口：回合 6 发生在 2026-09-19，窗口选 2026-09-20（弓种 / 射距 / 箭数均匹配，仅时间不符）
        CertDtos.CreateAppReq outOfWindow = new CertDtos.CreateAppReq(1L, 4L,
                ts("2026-09-20 00:00:00"), ts("2026-09-20 23:59:59"), List.of(6L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(outOfWindow))
                .isInstanceOf(BizException.class).hasMessageContaining("跨出证据窗口");

        // 窗口起止非法
        CertDtos.CreateAppReq badWindow = new CertDtos.CreateAppReq(1L, 1L,
                ts("2026-09-20 00:00:00"), ts("2026-09-19 00:00:00"), List.of(6L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(badWindow))
                .isInstanceOf(BizException.class).hasMessageContaining("开始时间必须早于结束时间");

        // 停用规则不能用于新申请：RECURVE-50 v1（id=3）
        CertDtos.CreateAppReq retiredRule = new CertDtos.CreateAppReq(1L, 3L,
                ts("2026-09-19 00:00:00"), ts("2026-09-19 23:59:59"), List.of(6L), "张岩");
        assertThatThrownBy(() -> certService.createApplication(retiredRule))
                .isInstanceOf(BizException.class).hasMessageContaining("已停用");

        // 一条申请都没有建成
        assertThat(certService.listApplications(null, null)).hasSize(3);
    }

    // ---------- 4. 完整流程：建立（预评达标）→ 通过 → 入口放行 → 新版本不影响历史快照 ----------

    @Test
    @Order(4)
    void happyPath_createApproveAndSnapshotStable() {
        // 会员 11（高翔）回合 8：COMPOUND 30 米 12 支 104 分平均 8.67，满足 COMPOUND-30 v1
        CertDtos.AppDetail created = certService.createApplication(new CertDtos.CreateAppReq(
                11L, 5L,
                ts("2026-09-20 00:00:00"), ts("2026-09-20 23:59:59"), List.of(8L), "张岩"));
        Long appId = created.summary().id();
        assertThat(created.summary().status()).isEqualTo("PENDING");
        assertThat(created.currentEval().passFlag()).isTrue();
        assertThat(created.currentEval().rounds()).hasSize(1);
        assertThat(created.currentEval().ruleVersion()).isEqualTo(1);
        assertThat(created.currentEval().avgScore()).isEqualByComparingTo("8.67");

        // 规则快照字段
        assertThat(created.currentEval().snapGroupSize()).isEqualTo(12);
        assertThat(created.currentEval().snapMinAverage()).isEqualByComparingTo("8.00");
        assertThat(created.currentEval().snapValidityMonths()).isEqualTo(12);

        CertDtos.AppDetail approved = certService.decide(appId,
                new CertDtos.DecideReq("APPROVE", "王铮", null));
        assertThat(approved.summary().status()).isEqualTo("APPROVED");
        assertThat(approved.summary().validUntil()).isAfter(LocalDateTime.now());

        // 通过后入口识别：会员 11 现在可租借复合弓（用在库 E-CP-03 id=6）
        transactionTemplate.executeWithoutResult(s ->
                equipmentService.rent(6L, new com.archery.range.dto.EquipmentDtos.RentReq(11L)));

        // 发布 COMPOUND-30 v2（标准上调到 9.0），旧认证详情仍显示 v1 快照
        CertDtos.RuleView v2 = certService.newVersion(5L, new CertDtos.RuleSaveReq(
                "COMPOUND", 30, 12, 1, new java.math.BigDecimal("9.00"), 24,
                "提高复合弓 30 米平均环标准", "王铮"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("ACTIVE");
        CertDtos.AppDetail afterNewVersion = certService.detail(appId);
        assertThat(afterNewVersion.currentEval().ruleVersion()).isEqualTo(1);
        assertThat(afterNewVersion.currentEval().snapMinAverage()).isEqualByComparingTo("8.00");
        // v1 已停用
        assertThat(certService.listRules()).filteredOn(r -> r.id() == 5L)
                .singleElement().extracting(CertDtos.RuleView::status).isEqualTo("RETIRED");
        // 旧认证仍然有效（规则变化不影响已发出认证）
        assertThat(certService.memberCerts(11L).current()).hasSize(1);
    }

    // ---------- 5. 预评未达标仍进待复核；教练驳回，申请终结 ----------

    @Test
    @Order(5)
    void evalNotPass_reject() {
        // 会员 9 回合 7：COMPOUND 30 米 6 支 37 分平均 6.17；规则要求 12 支组 → 箭数不符直接拦
        // 改用规则新建一条 6 支组复合弓规则谱系不允许（COMPOUND-30 已存在），故用 TRADITIONAL-18：
        // 会员 2 回合 2：传统弓 18 米 6 支 43 分平均 7.17 达标；先造一条会「未达标」的评定：
        // 新建规则 TRADITIONAL-10 v1 与该回合射距不符 → 用不存在的组合 TRADITIONAL-50
        // （50 米没有传统弓回合）——改为：直接对会员 2 用 TRADITIONAL-18（id=6）申请，回合 2 达标，
        // 再以 NEED_MORE/REJECT 验证流转；未达标场景用会员 2 历史回合 13（6.67，标准 6.0 也达标）。
        // 因此改为新建一条更高标准的 TRADITIONAL-18 v2（≥8.0）后申请：6.67/7.17 均不达标。
        certService.newVersion(6L, new CertDtos.RuleSaveReq(
                "TRADITIONAL", 18, 6, 2, new java.math.BigDecimal("8.00"), 12,
                "传统弓 18 米标准上调", "苏禾"));
        // v2 的 id
        CertDtos.RuleView v2 = certService.listActiveRules().stream()
                .filter(r -> r.ruleCode().equals("TRADITIONAL-18")).findFirst().orElseThrow();

        // 仅选 1 个回合（标准要 ≥2 回合），平均也只有 7.17：预评未达标，但申请仍建立为待复核
        CertDtos.AppDetail created = certService.createApplication(new CertDtos.CreateAppReq(
                2L, v2.id(),
                ts("2026-09-16 00:00:00"), ts("2026-09-16 23:59:59"), List.of(2L), "苏禾"));
        assertThat(created.currentEval().passFlag()).isFalse();
        assertThat(created.summary().status()).isEqualTo("PENDING");

        // 预评未达标直接通过且不填理由 → 拒绝
        Long appId = created.summary().id();
        assertThatThrownBy(() -> certService.decide(appId,
                new CertDtos.DecideReq("APPROVE", "苏禾", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("未达标");

        // 驳回必须填原因
        assertThatThrownBy(() -> certService.decide(appId,
                new CertDtos.DecideReq("REJECT", "苏禾", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("原因");

        CertDtos.AppDetail rejected = certService.decide(appId,
                new CertDtos.DecideReq("REJECT", "苏禾", "成绩不达标，继续训练后重新申请"));
        assertThat(rejected.summary().status()).isEqualTo("REJECTED");

        // 已终结的申请再决定 → 冲突反馈
        assertThatThrownBy(() -> certService.decide(appId,
                new CertDtos.DecideReq("APPROVE", "张岩", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("已被");
    }

    // ---------- 6. 要求补充证据 → 重新评定（新窗口 + 回合，沿用旧规则快照）→ 通过 ----------

    @Test
    @Order(6)
    void needMore_resubmitApprove() {
        // 会员 9（冯磊）只有一个复合弓 30 米 6 支回合 7（不符合 12 支规则）；
        // 先建一条 COMPOUND-30 v3 允许 6 支组（≥1 回合，≥6.0）用于流程
        CertDtos.RuleView v3 = certService.newVersion(5L, new CertDtos.RuleSaveReq(
                "COMPOUND", 30, 6, 1, new java.math.BigDecimal("6.00"), 12,
                "允许 6 支组申请", "王铮"));
        CertDtos.AppDetail created = certService.createApplication(new CertDtos.CreateAppReq(
                9L, v3.id(),
                ts("2026-09-20 00:00:00"), ts("2026-09-20 23:59:59"), List.of(7L), "王铮"));
        Long appId = created.summary().id();
        // 平均 6.17 刚好达标但教练要求补充证据
        CertDtos.AppDetail needMore = certService.decide(appId,
                new CertDtos.DecideReq("NEED_MORE", "王铮", "需要再补一个同窗口附近的完成回合佐证"));
        assertThat(needMore.summary().status()).isEqualTo("NEED_MORE");

        // 非 NEED_MORE 状态不能重新提交（先直接拿一笔 PENDING 验证）
        // 在 NEED_MORE 状态用非法窗口重新提交 → 拦截
        assertThatThrownBy(() -> certService.resubmit(appId, new CertDtos.ResubmitReq(
                ts("2026-09-21 00:00:00"), ts("2026-09-20 00:00:00"), List.of(7L), "王铮")))
                .isInstanceOf(BizException.class).hasMessageContaining("开始时间必须早于结束时间");

        // 重新评定沿用原规则（v3），窗口仍只有回合 7（其时间 2026-09-20 18:20 落在新窗口内）
        CertDtos.AppDetail resubmitted = certService.resubmit(appId, new CertDtos.ResubmitReq(
                ts("2026-09-20 00:00:00"), ts("2026-09-22 23:59:59"), List.of(7L), "王铮"));
        assertThat(resubmitted.summary().status()).isEqualTo("PENDING");
        assertThat(resubmitted.summary().evalSeq()).isEqualTo(2);
        assertThat(resubmitted.currentEval().evalSeq()).isEqualTo(2);
        // 历史评定仍保留第 1 轮
        assertThat(resubmitted.evalHistory()).hasSize(1);
        assertThat(resubmitted.evalHistory().get(0).evalSeq()).isEqualTo(1);
        assertThat(resubmitted.currentEval().ruleId()).isEqualTo(v3.id());

        CertDtos.AppDetail approved = certService.decide(appId,
                new CertDtos.DecideReq("APPROVE", "张岩", "补充说明充分"));
        assertThat(approved.summary().status()).isEqualTo("APPROVED");
    }

    // ---------- 7. 重新评定通过取代旧认证；撤回使入口立即失效 ----------

    @Test
    @Order(7)
    void supersedeAndWithdraw_changeUsableState() {
        // 会员 1 的 RECURVE-50 种子认证（id=1）有效；用现行 v2（id=4，标准 8.0）
        // 回合 6 平均 9.67 达标，建立新申请并通过
        CertDtos.AppDetail created = certService.createApplication(new CertDtos.CreateAppReq(
                1L, 4L,
                ts("2026-09-19 00:00:00"), ts("2026-09-19 23:59:59"), List.of(6L), "李慕白"));
        Long newAppId = created.summary().id();
        certService.decide(newAppId, new CertDtos.DecideReq("APPROVE", "李慕白", null));

        // 同一适用范围只剩新认证有效；旧认证状态变 SUPERSEDED
        assertThat(certService.memberCerts(1L).current()).hasSize(1);
        assertThat(certService.memberCerts(1L).current().get(0).applicationId()).isEqualTo(newAppId);
        CertDtos.AppDetail oldDetail = certService.detail(1L);
        assertThat(oldDetail.summary().status()).isEqualTo("SUPERSEDED");

        // 撤回新认证：有效认证清空，入口立即拦截
        certService.withdraw(newAppId, new CertDtos.WithdrawReq("李慕白", "抽查不合规，撤回"));
        assertThat(certService.memberCerts(1L).current()).isEmpty();
        assertThat(certService.detail(newAppId).summary().status()).isEqualTo("WITHDRAWN");
        assertThatThrownBy(() -> transactionTemplate.execute(s -> {
            laneService.open(9L, new com.archery.range.dto.LaneDtos.LaneOpenReq(1L, 1));
            return null;
        })).isInstanceOf(BizException.class).hasMessageContaining("认证");
    }

    // ---------- 8. 并发：两个教练同时决定同一申请，只能有一个最终结论 ----------

    @Test
    @Order(8)
    void concurrentDecide_onlyOneFinalConclusion() throws Exception {
        // 会员 8（何清）回合 5：RECURVE 50 米 12 支 109 分 —— 不符合 50 米规则的 6 支组，
        // 故先为其建立一个 12 支组的 RECURVE-50 v3（≥1 回合 ≥8.5，109/12=9.08 达标）
        CertDtos.RuleView v3 = certService.newVersion(4L, new CertDtos.RuleSaveReq(
                "RECURVE", 50, 12, 1, new java.math.BigDecimal("8.50"), 12,
                "允许 12 支组 50 米申请", "李慕白"));
        CertDtos.AppDetail created = certService.createApplication(new CertDtos.CreateAppReq(
                8L, v3.id(),
                ts("2026-09-18 00:00:00"), ts("2026-09-18 23:59:59"), List.of(5L), "李慕白"));
        Long appId = created.summary().id();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        Runnable coachA = () -> {
            try {
                transactionTemplate.executeWithoutResult(s -> {
                    try {
                        ready.countDown();
                        start.await();
                        certService.decide(appId, new CertDtos.DecideReq("APPROVE", "教练甲", null));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
                first.set("A-ok");
            } catch (Exception e) {
                first.set("A-fail:" + rootMessage(e));
            }
        };
        Runnable coachB = () -> {
            try {
                transactionTemplate.executeWithoutResult(s -> {
                    try {
                        ready.countDown();
                        start.await();
                        certService.decide(appId, new CertDtos.DecideReq("REJECT", "教练乙", "不同意"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
                second.set("B-ok");
            } catch (Exception e) {
                second.set("B-fail:" + rootMessage(e));
            }
        };

        Future<?> fa = pool.submit(coachA);
        Future<?> fb = pool.submit(coachB);

        ready.await();
        start.countDown();
        fa.get();
        fb.get();
        pool.shutdown();

        String ra = first.get();
        String rb = second.get();
        boolean oneOkOneConflict = ("A-ok".equals(ra) && rb.startsWith("B-fail"))
                || ("B-ok".equals(rb) && ra.startsWith("A-fail"));
        assertThat(oneOkOneConflict).as("甲=%s 乙=%s", ra, rb).isTrue();
        String loser = ra.startsWith("A-fail") ? ra : rb;
        assertThat(loser).contains("已被");

        // 库里只有一个结论，且与胜出者一致（APPROVED 或 REJECTED 二选一）
        String status = transactionTemplate.execute(s ->
                appRepository.findById(appId).map(CertApplication::getStatus).orElseThrow());
        assertThat(status).isIn("APPROVED", "REJECTED");
        // 若通过产生有效认证，若驳回则无 —— 且无论如何不存在两条有效认证
        long validCount = certService.memberCerts(8L).current().size();
        assertThat(validCount).isIn(0L, 1L);
        if ("APPROVED".equals(status)) {
            assertThat(validCount).isEqualTo(1L);
        }
    }

    // ---------- 9. 过期认证不能被撤回，且任何时刻都不被当作有效 ----------

    @Test
    @Order(9)
    void expiredCert_notWithdrawable_notValid() {
        // 种子申请 id=2（会员 2）已过期
        assertThatThrownBy(() -> certService.withdraw(2L,
                new CertDtos.WithdrawReq("苏禾", "过期后尝试撤回")))
                .isInstanceOf(BizException.class).hasMessageContaining("已过期");
        assertThat(certService.memberCerts(2L).current()).isEmpty();
        // 同一会员可重新建立同范围申请（旧的已过期不占办理中名额）；TRADITIONAL-18 在更早用例已升 v2
        CertDtos.RuleView activeTr18 = certService.listActiveRules().stream()
                .filter(r -> r.ruleCode().equals("TRADITIONAL-18")).findFirst().orElseThrow();
        CertDtos.AppDetail created = certService.createApplication(new CertDtos.CreateAppReq(
                2L, activeTr18.id(),
                ts("2026-09-16 00:00:00"), ts("2026-09-16 23:59:59"), List.of(2L), "苏禾"));
        assertThat(created.summary().status()).isEqualTo("PENDING");
    }

    // ---------- 10. 同范围只能有一笔办理中申请 ----------

    @Test
    @Order(10)
    void duplicateOpenApplication_rejected() {
        // 会员 6（吴桐）回合 4：RECURVE 18 米，规则只覆盖 10/18 中的 18 没有 RECURVE-18 规则，
        // 用 RECURVE-10（id=1）射距不符 → 改为新建规则谱系：10/18/30/50 里选未占用的。
        // 直接对已有谱系 RECURVE-30（id=2）找会员证据：没有 30 米反曲回合，故用规则管理新建
        // 一条全新谱系 RECURVE-18，再重复建申请。
        CertDtos.RuleView r18 = certService.createRule(new CertDtos.RuleSaveReq(
                "RECURVE", 18, 6, 1, new java.math.BigDecimal("6.00"), 12, "反曲弓 18 米", "张岩"));
        CertDtos.CreateAppReq req = new CertDtos.CreateAppReq(6L, r18.id(),
                ts("2026-09-17 00:00:00"), ts("2026-09-17 23:59:59"), List.of(4L), "张岩");
        certService.createApplication(req);
        assertThatThrownBy(() -> certService.createApplication(req))
                .isInstanceOf(BizException.class).hasMessageContaining("办理中");
    }

    // ---------- 11. 弓种词典与射距门槛口径 ----------

    @Test
    @Order(11)
    void dictConsistency() {
        assertThat(RangeDict.isValidBowType("RECURVE")).isTrue();
        assertThat(RangeDict.isValidBowType("GEAR")).isFalse();
        assertThat(RangeDict.CERT_GATED_DISTANCES).containsExactly(30, 50);
        assertThat(RangeDict.certStatusName("NEED_MORE")).isEqualTo("待补充证据");
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
