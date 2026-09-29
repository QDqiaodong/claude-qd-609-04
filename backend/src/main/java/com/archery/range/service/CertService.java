package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.CertApplication;
import com.archery.range.domain.CertEval;
import com.archery.range.domain.CertEvalRound;
import com.archery.range.domain.CertLog;
import com.archery.range.domain.CertRule;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.dto.CertDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.CertEvalRepository;
import com.archery.range.repository.CertEvalRoundRepository;
import com.archery.range.repository.CertLogRepository;
import com.archery.range.repository.CertRuleRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;

/**
 * 弓种能力认证。
 *
 * 关键设计：
 * 1. 规则带版本 —— 弓种 + 射距构成规则谱系（ruleCode + version），调整标准只发布新版本、
 *    旧版本停用；每次评定把规则快照（箭数 / 最少回合 / 通过标准 / 有效期）固化进 cert_eval，
 *    规则再变也不改写已发出的认证历史。
 * 2. 证据窗口硬校验 —— 证据回合必须同一会员、已完成（SUBMITTED）、弓种与射距匹配、
 *    每组箭数与规则一致且时间落在窗口内；任一不符逐条给出原因，未完成回合绝不会被悄悄计入。
 * 3. 状态机 —— 建立申请并完成系统预评即进入待复核；教练可通过 / 驳回 / 要求补充证据；
 *    补充证据产生新一轮评定（旧评定保留）；通过后可撤回，重新评定通过会取代同范围的旧认证。
 *    过期是派生状态：已通过但 validUntil 已过即无效，任何入口都不再承认。
 * 4. 并发一致 —— 复核先对申请行加悲观锁、通过时再对会员行加锁（固定加锁顺序：申请 → 会员），
 *    结论用「仅待复核可写」的原子更新；两个教练同时决定只落一个最终结论，后到者得到明确冲突反馈。
 */
@Service
public class CertService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> OPEN_STATUSES = List.of("PENDING", "NEED_MORE");

    private final CertApplicationRepository appRepository;
    private final CertRuleRepository ruleRepository;
    private final CertEvalRepository evalRepository;
    private final CertEvalRoundRepository evalRoundRepository;
    private final CertLogRepository logRepository;
    private final MemberRepository memberRepository;
    private final RoundRepository roundRepository;

    public CertService(CertApplicationRepository appRepository,
            CertRuleRepository ruleRepository,
            CertEvalRepository evalRepository,
            CertEvalRoundRepository evalRoundRepository,
            CertLogRepository logRepository,
            MemberRepository memberRepository,
            RoundRepository roundRepository) {
        this.appRepository = appRepository;
        this.ruleRepository = ruleRepository;
        this.evalRepository = evalRepository;
        this.evalRoundRepository = evalRoundRepository;
        this.logRepository = logRepository;
        this.memberRepository = memberRepository;
        this.roundRepository = roundRepository;
    }

    // ---------------- 选项 ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        List<Map<String, Object>> bowTypes = List.of("RECURVE", "COMPOUND", "TRADITIONAL").stream()
                .map(code -> Map.<String, Object>of("code", code, "name", RangeDict.bowTypeName(code)))
                .toList();
        options.put("bowTypes", bowTypes);
        options.put("distances", RangeDict.DISTANCES);
        options.put("groupSizes", RangeDict.GROUP_SIZES);
        options.put("gatedDistances", RangeDict.CERT_GATED_DISTANCES);
        options.put("activeRules", listActiveRules());
        return options;
    }

    // ---------------- 规则（版本化） ----------------

    @Transactional(readOnly = true)
    public List<CertDtos.RuleView> listRules() {
        return ruleRepository.findAllByOrderByRuleCodeAscVersionDesc().stream().map(CertService::toRuleView).toList();
    }

    @Transactional(readOnly = true)
    public List<CertDtos.RuleView> listActiveRules() {
        return ruleRepository.findByStatusOrderByBowTypeAscDistanceAsc("ACTIVE")
                .stream().map(CertService::toRuleView).toList();
    }

    /** 新建规则谱系 v1（同弓种 + 射距已有谱系时，调整标准必须走「发布新版本」） */
    @Transactional
    public CertDtos.RuleView createRule(CertDtos.RuleSaveReq req) {
        String operator = requireName(req.operator(), "教练");
        validateRuleFields(req);
        String bowType = req.bowType().trim().toUpperCase();
        String ruleCode = bowType + "-" + req.distance();
        if (ruleRepository.existsByRuleCode(ruleCode)) {
            throw new BizException("「" + RangeDict.bowTypeName(bowType) + " " + req.distance()
                    + " 米」已存在规则谱系，调整标准请发布新版本，不能重建覆盖历史版本");
        }
        LocalDateTime now = LocalDateTime.now();
        CertRule rule = new CertRule();
        rule.setRuleCode(ruleCode);
        rule.setVersion(1);
        rule.setBowType(bowType);
        rule.setDistance(req.distance());
        rule.setGroupSize(req.groupSize());
        rule.setMinRounds(req.minRounds());
        rule.setMinAverage(req.minAverage().setScale(2, RoundingMode.HALF_UP));
        rule.setValidityMonths(req.validityMonths());
        rule.setStatus("ACTIVE");
        rule.setNote(trimOrNull(req.note()));
        rule.setCreatedBy(operator);
        rule.setCreatedAt(now);
        return toRuleView(ruleRepository.save(rule));
    }

    /** 发布新版本：沿用谱系号，version + 1，上一现行版本自动停用（历史认证仍引用旧版本） */
    @Transactional
    public CertDtos.RuleView newVersion(Long ruleId, CertDtos.RuleSaveReq req) {
        String operator = requireName(req.operator(), "教练");
        CertRule base = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BizException("规则不存在：id=" + ruleId));
        validateRuleFields(req);
        String bowType = req.bowType().trim().toUpperCase();
        if (!bowType.equals(base.getBowType()) || !Objects.equals(req.distance(), base.getDistance())) {
            throw new BizException("发布新版本不能改变弓种与射距；新的弓种 / 射距请新建规则谱系");
        }
        CertRule latest = ruleRepository.findFirstByRuleCodeOrderByVersionDesc(base.getRuleCode()).orElse(base);
        // 同一谱系的旧版本全部停用，保证新申请只能选最新版本
        ruleRepository.findByRuleCodeOrderByVersionDesc(base.getRuleCode()).stream()
                .filter(rule -> "ACTIVE".equals(rule.getStatus()))
                .forEach(rule -> rule.setStatus("RETIRED"));

        CertRule rule = new CertRule();
        rule.setRuleCode(latest.getRuleCode());
        rule.setVersion(latest.getVersion() + 1);
        rule.setBowType(bowType);
        rule.setDistance(req.distance());
        rule.setGroupSize(req.groupSize());
        rule.setMinRounds(req.minRounds());
        rule.setMinAverage(req.minAverage().setScale(2, RoundingMode.HALF_UP));
        rule.setValidityMonths(req.validityMonths());
        rule.setStatus("ACTIVE");
        rule.setNote(trimOrNull(req.note()));
        rule.setCreatedBy(operator);
        rule.setCreatedAt(LocalDateTime.now());
        return toRuleView(ruleRepository.save(rule));
    }

    /** 停用现行规则：停用后不能再用于新申请，已按该版本发出的认证不受影响 */
    @Transactional
    public CertDtos.RuleView retireRule(Long ruleId, CertDtos.RuleOperatorReq req) {
        requireName(req.operator(), "教练");
        CertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BizException("规则不存在：id=" + ruleId));
        if ("RETIRED".equals(rule.getStatus())) {
            throw new BizException("规则 " + rule.getRuleCode() + " v" + rule.getVersion() + " 已停用，无需重复操作");
        }
        rule.setStatus("RETIRED");
        return toRuleView(ruleRepository.save(rule));
    }

    private void validateRuleFields(CertDtos.RuleSaveReq req) {
        String bowType = req.bowType() == null ? "" : req.bowType().trim().toUpperCase();
        if (!RangeDict.isValidBowType(bowType)) {
            throw new BizException("弓种只能是 反曲弓 / 复合弓 / 传统弓");
        }
        if (!RangeDict.isValidDistance(req.distance())) {
            throw new BizException("射距只能是 10 / 18 / 30 / 50 米");
        }
        if (!RangeDict.isValidGroupSize(req.groupSize())) {
            throw new BizException("每组箭数只能是 6 或 12");
        }
        if (req.minRounds() == null || req.minRounds() < 1 || req.minRounds() > 20) {
            throw new BizException("证据窗口最少回合数需在 1 ~ 20 之间");
        }
        if (req.minAverage() == null
                || req.minAverage().compareTo(BigDecimal.ZERO) < 0
                || req.minAverage().compareTo(BigDecimal.TEN) > 0) {
            throw new BizException("平均环通过标准需在 0 ~ 10 之间");
        }
        if (req.validityMonths() == null || req.validityMonths() < 1 || req.validityMonths() > 60) {
            throw new BizException("认证有效期需在 1 ~ 60 个月之间");
        }
    }

    // ---------------- 申请列表 / 详情 ----------------

    @Transactional(readOnly = true)
    public List<CertDtos.AppSummary> listApplications(Long memberId, String status) {
        List<CertApplication> apps;
        if (memberId != null && status != null && !status.isBlank()) {
            apps = appRepository.findByMemberIdOrderByIdDesc(memberId).stream()
                    .filter(app -> app.getStatus().equals(status)).toList();
        } else if (memberId != null) {
            apps = appRepository.findByMemberIdOrderByIdDesc(memberId);
        } else if (status != null && !status.isBlank()) {
            apps = appRepository.findByStatusOrderByIdDesc(status);
        } else {
            apps = appRepository.findAllByOrderByIdDesc();
        }
        LocalDateTime now = LocalDateTime.now();
        return apps.stream().map(app -> toSummary(app, now)).toList();
    }

    @Transactional(readOnly = true)
    public CertDtos.AppDetail detail(Long id) {
        CertApplication app = appRepository.findById(id)
                .orElseThrow(() -> new BizException("认证申请不存在：id=" + id));
        LocalDateTime now = LocalDateTime.now();
        List<CertEval> evals = evalRepository.findByApplicationIdOrderByEvalSeqDesc(id);
        CertEval current = evals.stream()
                .filter(item -> item.getEvalSeq().equals(app.getEvalSeq()))
                .findFirst()
                .orElse(evals.isEmpty() ? null : evals.get(0));
        CertDtos.EvalView currentView = current == null ? null : toEvalView(current, true);
        List<CertDtos.EvalView> history = evals.stream()
                .filter(item -> !item.getEvalSeq().equals(app.getEvalSeq()))
                .map(item -> toEvalView(item, false))
                .toList();
        List<CertDtos.LogItem> logs = logRepository.findByApplicationIdOrderByIdAsc(id).stream()
                .map(this::toLogItem)
                .toList();
        return new CertDtos.AppDetail(toSummary(app, now), currentView, history, logs);
    }

    // ---------------- 建立申请（校验证据窗口 + 系统预评） ----------------

    @Transactional
    public CertDtos.AppDetail createApplication(CertDtos.CreateAppReq req) {
        String operator = requireName(req.operator(), "教练");
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(() -> new BizException("会员不存在：id=" + req.memberId()));
        CertRule rule = ruleRepository.findById(req.ruleId())
                .orElseThrow(() -> new BizException("认证规则不存在：id=" + req.ruleId()));
        if (!"ACTIVE".equals(rule.getStatus())) {
            throw new BizException("规则 " + rule.getRuleCode() + " v" + rule.getVersion()
                    + " 已停用，新的认证申请只能选用现行版本（历史评定仍保留旧版本快照）");
        }
        validateWindow(req.windowStart(), req.windowEnd());
        if (req.roundIds() == null || req.roundIds().isEmpty()) {
            throw new BizException("请至少选择一个证据回合（必须是该会员已完成的计分回合）");
        }
        if (appRepository.countOpenByScope(member.getId(), rule.getBowType(), rule.getDistance(), OPEN_STATUSES) > 0) {
            throw new BizException("会员 [" + member.getName() + "] 已有一笔「"
                    + RangeDict.bowTypeName(rule.getBowType()) + " " + rule.getDistance()
                    + " 米」申请在办理中，请先完成当前复核，或补充证据后重新评定");
        }

        List<Round> rounds = validateEvidence(member, rule, req.windowStart(), req.windowEnd(), req.roundIds());

        LocalDateTime now = LocalDateTime.now();
        CertApplication app = new CertApplication();
        app.setAppNo(nextAppNo());
        app.setMember(member);
        app.setBowType(rule.getBowType());
        app.setDistance(rule.getDistance());
        app.setWindowStart(req.windowStart());
        app.setWindowEnd(req.windowEnd());
        app.setStatus("PENDING");
        app.setEvalSeq(1);
        app.setCreatedBy(operator);
        app.setCreatedAt(now);
        appRepository.save(app);

        CertEval eval = evaluate(app, rule, 1, req.windowStart(), req.windowEnd(), rounds, operator, now);

        log(app.getId(), "CREATE", operator, "COACH",
                "建立认证申请：" + member.getName() + " · " + RangeDict.bowTypeName(rule.getBowType()) + " "
                        + rule.getDistance() + " 米 · 规则 " + rule.getRuleCode() + " v" + rule.getVersion()
                        + "；证据窗口 " + fmtTime(req.windowStart()) + " ~ " + fmtTime(req.windowEnd()));
        log(app.getId(), "EVAL", "认证系统", "SYSTEM", eval.getEvalMessage() + "，提交教练复核");
        return detail(app.getId());
    }

    // ---------------- 复核：通过 / 驳回 / 要求补充证据（并发只能落一个结论） ----------------

    @Transactional
    public CertDtos.AppDetail decide(Long id, CertDtos.DecideReq req) {
        String reviewer = requireName(req.operator(), "复核教练");
        String action = req.action() == null ? "" : req.action().trim().toUpperCase();
        if (!RangeDict.isValidCertDecision(action)) {
            throw new BizException("复核动作只能是 通过 / 驳回 / 要求补充证据");
        }
        String note = trimOrEmpty(req.note());
        if (("REJECT".equals(action) || "NEED_MORE".equals(action)) && note.isEmpty()) {
            throw new BizException("驳回或要求补充证据时必须填写原因");
        }

        // 锁申请行：两个教练同时决定在此串行，后到者只能看到对方的最终结论
        CertApplication app = lockApplication(id);
        if (!"PENDING".equals(app.getStatus())) {
            throw conflictMessage(app);
        }
        CertEval current = evalRepository.findByApplicationIdAndEvalSeq(id, app.getEvalSeq())
                .orElseThrow(() -> new BizException("评定记录缺失，请刷新后重试"));
        if ("APPROVE".equals(action) && !Boolean.TRUE.equals(current.getPassFlag()) && note.isEmpty()) {
            throw new BizException("系统预评结果为未达标；教练仍要通过时必须填写复核意见说明理由");
        }

        LocalDateTime now = LocalDateTime.now();
        if ("APPROVE".equals(action)) {
            // 通过再锁会员行：同一适用范围的两笔申请并发通过也会串行，杜绝两条互相矛盾的有效认证
            memberRepository.findByIdForUpdate(app.getMember().getId()).orElseThrow();
            LocalDateTime validFrom = now;
            LocalDateTime validUntil = now.plusMonths(current.getSnapValidityMonths());
            int updated = appRepository.decideIfPending(id, "APPROVED", reviewer, now, emptyToNull(note),
                    validFrom, validUntil);
            if (updated == 0) {
                throw conflictMessage(app);
            }
            app.setStatus("APPROVED");
            app.setReviewedBy(reviewer);
            app.setReviewedAt(now);
            app.setReviewNote(emptyToNull(note));
            app.setValidFrom(validFrom);
            app.setValidUntil(validUntil);
            // 重新评定通过：同一会员 + 弓种 + 射距仍有效的旧认证立即被取代
            supersedePrevious(app, reviewer, now);
            log(id, "APPROVE", reviewer, "COACH",
                    "复核通过" + (note.isEmpty() ? "" : "：" + note)
                            + "；认证 " + fmtTime(validFrom) + " 生效，有效期至 " + fmtTime(validUntil)
                            + "；适用范围：" + RangeDict.bowTypeName(app.getBowType())
                            + " " + app.getDistance() + " 米及以内射距");
        } else if ("REJECT".equals(action)) {
            int updated = appRepository.decideIfPending(id, "REJECTED", reviewer, now, note, null, null);
            if (updated == 0) {
                throw conflictMessage(app);
            }
            app.setStatus("REJECTED");
            app.setReviewedBy(reviewer);
            app.setReviewNote(note);
            app.setReviewedAt(now);
            log(id, "REJECT", reviewer, "COACH", "复核驳回：" + note);
        } else {
            int updated = appRepository.decideIfPending(id, "NEED_MORE", reviewer, now, note, null, null);
            if (updated == 0) {
                throw conflictMessage(app);
            }
            app.setStatus("NEED_MORE");
            app.setReviewedBy(reviewer);
            app.setReviewNote(note);
            app.setReviewedAt(now);
            log(id, "NEED_MORE", reviewer, "COACH", "要求补充证据：" + note);
        }
        return detail(id);
    }

    // ---------------- 待补充证据：重新提交并按原规则快照重新评定 ----------------

    @Transactional
    public CertDtos.AppDetail resubmit(Long id, CertDtos.ResubmitReq req) {
        String operator = requireName(req.operator(), "教练");
        CertApplication app = lockApplication(id);
        if (!"NEED_MORE".equals(app.getStatus())) {
            throw new BizException("申请当前为「" + RangeDict.certStatusName(displayStatus(app, LocalDateTime.now()))
                    + "」，只有待补充证据的申请能补充证据后重新评定");
        }
        validateWindow(req.windowStart(), req.windowEnd());
        if (req.roundIds() == null || req.roundIds().isEmpty()) {
            throw new BizException("请至少补充一个证据回合");
        }
        // 重新评定必须沿用申请当时选定的规则版本（规则快照来源），即使该版本已停用
        CertEval last = evalRepository.findByApplicationIdAndEvalSeq(id, app.getEvalSeq())
                .orElseThrow(() -> new BizException("评定记录缺失，请刷新后重试"));
        CertRule rule = ruleRepository.findById(last.getRuleId())
                .orElseThrow(() -> new BizException("评定时使用的规则版本已缺失，无法重新评定"));
        List<Round> rounds = validateEvidence(app.getMember(), rule,
                req.windowStart(), req.windowEnd(), req.roundIds());

        int updated = appRepository.resubmitIfNeedMore(id, req.windowStart(), req.windowEnd());
        if (updated == 0) {
            throw conflictMessage(app);
        }
        app.setStatus("PENDING");
        app.setWindowStart(req.windowStart());
        app.setWindowEnd(req.windowEnd());
        app.setEvalSeq(app.getEvalSeq() + 1);
        app.setReviewedBy(null);
        app.setReviewedAt(null);
        app.setReviewNote(null);
        LocalDateTime now = LocalDateTime.now();
        CertEval eval = evaluate(app, rule, app.getEvalSeq(), req.windowStart(), req.windowEnd(), rounds, operator, now);
        log(id, "RESUBMIT", operator, "COACH",
                "补充证据重新评定（第 " + app.getEvalSeq() + " 轮）：证据窗口 "
                        + fmtTime(req.windowStart()) + " ~ " + fmtTime(req.windowEnd())
                        + "，仍按规则 " + rule.getRuleCode() + " v" + rule.getVersion() + " 快照评定");
        log(id, "EVAL", "认证系统", "SYSTEM", eval.getEvalMessage() + "，提交教练复核");
        return detail(id);
    }

    // ---------------- 撤回已通过认证 ----------------

    @Transactional
    public CertDtos.AppDetail withdraw(Long id, CertDtos.WithdrawReq req) {
        String operator = requireName(req.operator(), "教练");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("撤回认证必须填写原因");
        }
        CertApplication app = lockApplication(id);
        LocalDateTime now = LocalDateTime.now();
        if (!"APPROVED".equals(app.getStatus())) {
            throw new BizException("申请当前为「" + RangeDict.certStatusName(displayStatus(app, now))
                    + "」，只有已通过的认证能撤回");
        }
        if (app.getValidUntil() == null || !app.getValidUntil().isAfter(now)) {
            throw new BizException("该认证已过期，无需撤回（历史记录保留为已过期）");
        }
        // 与「通过」相同的加锁顺序（申请 → 会员），避免与重新评定并发
        memberRepository.findByIdForUpdate(app.getMember().getId()).orElseThrow();
        int updated = appRepository.withdrawIfValid(id, operator, now, reason, now);
        if (updated == 0) {
            throw conflictMessage(app);
        }
        app.setStatus("WITHDRAWN");
        app.setWithdrawnBy(operator);
        app.setWithdrawnAt(now);
        app.setWithdrawReason(reason);
        log(id, "WITHDRAW", operator, "COACH", "撤回认证：" + reason);
        return detail(id);
    }

    // ---------------- 会员认证视图 / 入口识别 ----------------

    @Transactional(readOnly = true)
    public CertDtos.MemberCerts memberCerts(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BizException("会员不存在：id=" + memberId));
        LocalDateTime now = LocalDateTime.now();
        List<CertDtos.CertItem> current = validApplications(memberId, now).stream()
                .map(app -> toCertItem(app, now))
                .toList();
        List<CertDtos.AppSummary> history = appRepository.findByMemberIdOrderByIdDesc(memberId).stream()
                .map(app -> toSummary(app, now))
                .toList();
        return new CertDtos.MemberCerts(memberId, member.getName(), current, history);
    }

    /**
     * 箭道开台入口：30 / 50 米认证射距要求会员持有覆盖该射距的有效认证
     * （认证射距 ≥ 箭道射距，即 50 米认证可用于 30 米）。认证过期 / 撤回 / 被取代后立即拦截。
     */
    @Transactional(readOnly = true)
    public void requireDistanceCert(Member member, Integer distance) {
        if (!RangeDict.CERT_GATED_DISTANCES.contains(distance)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        CertApplication cert = validApplications(member.getId(), now).stream()
                .filter(app -> app.getDistance() >= distance)
                .findFirst()
                .orElse(null);
        if (cert == null) {
            throw new BizException("会员 [" + member.getName() + "] 未持有覆盖 " + distance
                    + " 米射距的有效弓种认证，不能在该箭道开台；请先在「认证」页完成弓种能力认证");
        }
    }

    /**
     * 器材租借入口：租借弓类器材要求会员持有该弓种的有效认证（任一射距均可）。
     */
    @Transactional(readOnly = true)
    public void requireBowCert(Member member, String bowType) {
        if (!RangeDict.isValidBowType(bowType)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        CertApplication cert = validApplications(member.getId(), now).stream()
                .filter(app -> bowType.equals(app.getBowType()))
                .findFirst()
                .orElse(null);
        if (cert == null) {
            throw new BizException("会员 [" + member.getName() + "] 未持有有效的「"
                    + RangeDict.bowTypeName(bowType) + "」认证，不能租借该弓种器材；"
                    + "请先在「认证」页完成弓种能力认证");
        }
    }

    // ---------------- 内部：证据校验与系统预评 ----------------

    /**
     * 证据窗口逐条硬校验，任一不符都带着回合号说明原因；未完成 / 不匹配回合绝不会被采信。
     */
    private List<Round> validateEvidence(Member member, CertRule rule,
            java.time.LocalDateTime windowStart, java.time.LocalDateTime windowEnd, List<Long> roundIds) {
        // 去重并保持提交顺序
        List<Long> ids = new ArrayList<>();
        for (Long roundId : roundIds) {
            if (roundId != null && !ids.contains(roundId)) {
                ids.add(roundId);
            }
        }
        Map<Long, Round> roundMap = new LinkedHashMap<>();
        roundRepository.findAllById(ids).forEach(round -> roundMap.put(round.getId(), round));

        List<String> problems = new ArrayList<>();
        List<Round> valid = new ArrayList<>();
        for (Long roundId : ids) {
            Round round = roundMap.get(roundId);
            if (round == null) {
                problems.add("回合不存在：id=" + roundId);
                continue;
            }
            String label = "回合 [" + round.getRoundNo() + "]";
            if (round.getMember() == null || !round.getMember().getId().equals(member.getId())) {
                String owner = round.getMember() == null ? "其他会员" : round.getMember().getName();
                problems.add(label + " 属于会员 " + owner + "，不能作为 " + member.getName() + " 的认证证据");
                continue;
            }
            if (!"SUBMITTED".equals(round.getStatus())) {
                problems.add(label + " 尚未完成（当前：" + RangeDict.roundStatusName(round.getStatus())
                        + "），证据窗口只采信已完成的计分回合");
                continue;
            }
            if (!rule.getBowType().equals(round.getBowType())) {
                problems.add(label + " 使用弓种为「" + RangeDict.bowTypeName(round.getBowType())
                        + "」，与申请弓种「" + RangeDict.bowTypeName(rule.getBowType()) + "」不匹配");
                continue;
            }
            if (round.getLane() == null || !Objects.equals(round.getLane().getDistance(), rule.getDistance())) {
                Integer actual = round.getLane() == null ? null : round.getLane().getDistance();
                problems.add(label + " 射距为 " + actual + " 米，与规则要求的 " + rule.getDistance() + " 米不符");
                continue;
            }
            if (!Objects.equals(round.getArrowCount(), rule.getGroupSize())) {
                problems.add(label + " 每组 " + round.getArrowCount() + " 支箭，与规则要求的每组 "
                        + rule.getGroupSize() + " 支不符");
                continue;
            }
            if (round.getStartTime().isBefore(windowStart) || round.getStartTime().isAfter(windowEnd)) {
                problems.add(label + " 开始时间 " + fmtTime(round.getStartTime()) + " 跨出证据窗口（"
                        + fmtTime(windowStart) + " ~ " + fmtTime(windowEnd) + "）");
                continue;
            }
            valid.add(round);
        }
        if (!problems.isEmpty()) {
            throw new BizException("证据回合校验未通过：" + String.join("；", problems));
        }
        valid.sort(java.util.Comparator.comparing(Round::getStartTime));
        return valid;
    }

    private CertEval evaluate(CertApplication app, CertRule rule, int evalSeq,
            java.time.LocalDateTime windowStart, java.time.LocalDateTime windowEnd,
            List<Round> rounds, String operator, LocalDateTime now) {
        int roundCount = rounds.size();
        int arrowTotal = rounds.stream().mapToInt(Round::getArrowCount).sum();
        int totalScore = rounds.stream().mapToInt(r -> r.getTotalScore() == null ? 0 : r.getTotalScore()).sum();
        BigDecimal avg = arrowTotal == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(totalScore)
                        .divide(BigDecimal.valueOf(arrowTotal), 2, RoundingMode.HALF_UP);
        boolean enoughRounds = roundCount >= rule.getMinRounds();
        boolean avgPass = avg.compareTo(rule.getMinAverage()) >= 0;
        boolean pass = enoughRounds && avgPass;

        StringBuilder message = new StringBuilder();
        message.append("证据窗口内 ").append(roundCount).append(" 个回合共 ").append(arrowTotal)
                .append(" 支箭，总环 ").append(totalScore).append("，平均 ").append(avg.toPlainString())
                .append(" 环/支；通过标准：≥").append(rule.getMinRounds()).append(" 回合且平均 ≥")
                .append(rule.getMinAverage().toPlainString()).append("，系统预评");
        if (pass) {
            message.append("达标");
        } else {
            message.append("未达标：");
            List<String> misses = new ArrayList<>();
            if (!enoughRounds) {
                misses.add("回合数 " + roundCount + " 低于要求的 " + rule.getMinRounds());
            }
            if (!avgPass) {
                misses.add("平均 " + avg.toPlainString() + " 环低于要求的 " + rule.getMinAverage().toPlainString());
            }
            message.append(String.join("；", misses));
        }

        CertEval eval = new CertEval();
        eval.setApplicationId(app.getId());
        eval.setEvalSeq(evalSeq);
        eval.setRuleId(rule.getId());
        eval.setRuleCode(rule.getRuleCode());
        eval.setRuleVersion(rule.getVersion());
        eval.setSnapGroupSize(rule.getGroupSize());
        eval.setSnapMinRounds(rule.getMinRounds());
        eval.setSnapMinAverage(rule.getMinAverage());
        eval.setSnapValidityMonths(rule.getValidityMonths());
        eval.setWindowStart(windowStart);
        eval.setWindowEnd(windowEnd);
        eval.setRoundCount(roundCount);
        eval.setArrowTotal(arrowTotal);
        eval.setTotalScore(totalScore);
        eval.setAvgScore(avg);
        eval.setPassFlag(pass);
        eval.setEvalMessage(clip(message.toString()));
        eval.setCreatedBy(operator);
        eval.setCreatedAt(now);
        evalRepository.save(eval);

        for (Round round : rounds) {
            evalRoundRepository.save(new CertEvalRound(eval.getId(), round.getId()));
        }
        return eval;
    }

    /** 重新评定通过后，同一适用范围仍有效的旧认证标记为「已被取代」并留痕 */
    private void supersedePrevious(CertApplication approved, String reviewer, LocalDateTime now) {
        List<CertApplication> previous = appRepository
                .findByMemberIdAndBowTypeAndDistanceAndStatus(
                        approved.getMember().getId(), approved.getBowType(), approved.getDistance(), "APPROVED")
                .stream()
                .filter(app -> !app.getId().equals(approved.getId()))
                .filter(app -> app.getValidUntil() != null && app.getValidUntil().isAfter(now))
                .toList();
        for (CertApplication old : previous) {
            old.setStatus("SUPERSEDED");
            appRepository.save(old);
            log(old.getId(), "SUPERSEDE", "认证系统", "SYSTEM",
                    "新认证 " + approved.getAppNo() + "（复核教练 " + reviewer
                            + "）通过，同一适用范围（" + RangeDict.bowTypeName(approved.getBowType()) + " "
                            + approved.getDistance() + " 米）的旧认证被取代，不再有效");
        }
    }

    private List<CertApplication> validApplications(Long memberId, LocalDateTime now) {
        // 有效认证 = 已通过 且 未过期；撤回 / 驳回 / 待补充 / 被取代天然不在其中
        return appRepository.findByMemberIdAndStatusAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                memberId, "APPROVED", now);
    }

    // ---------------- 内部工具 ----------------

    private CertApplication lockApplication(Long id) {
        return appRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("认证申请不存在：id=" + id));
    }

    /** 并发冲突反馈：后到的教练必须清楚申请已被谁处理成什么状态 */
    private BizException conflictMessage(CertApplication app) {
        String by = app.getReviewedBy() == null ? "其他教练" : app.getReviewedBy();
        String at = app.getReviewedAt() == null ? "" : "（" + fmtTime(app.getReviewedAt()) + "）";
        return new BizException("该申请已被 " + by + at + " 处理为「"
                + RangeDict.certStatusName(displayStatus(app, LocalDateTime.now()))
                + "」，刷新后查看最新结果，不能重复提交结论");
    }

    private void validateWindow(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        if (start == null || end == null) {
            throw new BizException("请选择证据窗口的开始与结束时间");
        }
        if (!start.isBefore(end)) {
            throw new BizException("证据窗口开始时间必须早于结束时间");
        }
        if (start.isAfter(LocalDateTime.now())) {
            throw new BizException("证据窗口开始时间不能晚于当前时间（证据必须来自已完成的历史回合）");
        }
    }

    private void log(Long applicationId, String action, String operator, String role, String detail) {
        CertLog entry = new CertLog();
        entry.setApplicationId(applicationId);
        entry.setAction(action);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(clip(detail));
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private String nextAppNo() {
        String no = "C" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (appRepository.existsByAppNo(no)) {
            no = "C" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private static String displayStatus(CertApplication app, LocalDateTime now) {
        if ("APPROVED".equals(app.getStatus())
                && app.getValidUntil() != null && !app.getValidUntil().isAfter(now)) {
            return "EXPIRED";
        }
        return app.getStatus();
    }

    private CertDtos.AppSummary toSummary(CertApplication app, LocalDateTime now) {
        String display = displayStatus(app, now);
        CertEval latest = evalRepository.findByApplicationIdAndEvalSeq(app.getId(), app.getEvalSeq()).orElse(null);
        return new CertDtos.AppSummary(
                app.getId(),
                app.getAppNo(),
                app.getMember() == null ? null : app.getMember().getId(),
                app.getMember() == null ? "" : app.getMember().getName(),
                app.getBowType(),
                RangeDict.bowTypeName(app.getBowType()),
                app.getDistance(),
                app.getWindowStart(),
                app.getWindowEnd(),
                app.getStatus(),
                RangeDict.certStatusName(app.getStatus()),
                display,
                RangeDict.certStatusName(display),
                app.getEvalSeq(),
                latest == null ? null : latest.getPassFlag(),
                latest == null ? null : latest.getAvgScore(),
                app.getCreatedBy(),
                app.getCreatedAt(),
                app.getReviewedBy(),
                app.getReviewedAt(),
                app.getReviewNote(),
                app.getValidFrom(),
                app.getValidUntil(),
                app.getWithdrawnBy(),
                app.getWithdrawnAt(),
                app.getWithdrawReason());
    }

    private CertDtos.EvalView toEvalView(CertEval eval, boolean withRounds) {
        List<CertDtos.EvidenceRound> rounds = List.of();
        if (withRounds) {
            List<Long> roundIds = evalRoundRepository.findByEvalId(eval.getId()).stream()
                    .map(CertEvalRound::getRoundId)
                    .toList();
            if (!roundIds.isEmpty()) {
                Map<Long, Round> roundMap = new LinkedHashMap<>();
                roundRepository.findAllById(roundIds).forEach(round -> roundMap.put(round.getId(), round));
                rounds = roundIds.stream()
                        .map(roundMap::get)
                        .filter(Objects::nonNull)
                        .sorted(java.util.Comparator.comparing(Round::getStartTime))
                        .map(this::toEvidenceRound)
                        .toList();
            }
        }
        return new CertDtos.EvalView(
                eval.getId(),
                eval.getEvalSeq(),
                eval.getRuleId(),
                eval.getRuleCode(),
                eval.getRuleVersion(),
                eval.getSnapGroupSize(),
                eval.getSnapMinRounds(),
                eval.getSnapMinAverage(),
                eval.getSnapValidityMonths(),
                eval.getWindowStart(),
                eval.getWindowEnd(),
                eval.getRoundCount(),
                eval.getArrowTotal(),
                eval.getTotalScore(),
                eval.getAvgScore(),
                eval.getPassFlag(),
                eval.getEvalMessage(),
                eval.getCreatedBy(),
                eval.getCreatedAt(),
                rounds);
    }

    private CertDtos.EvidenceRound toEvidenceRound(Round round) {
        return new CertDtos.EvidenceRound(
                round.getId(),
                round.getRoundNo(),
                round.getStartTime(),
                round.getLane() == null ? "" : round.getLane().getLaneNo(),
                round.getLane() == null ? null : round.getLane().getDistance(),
                round.getBowType(),
                RangeDict.bowTypeName(round.getBowType()),
                round.getArrowCount(),
                round.getTotalScore(),
                round.getAverageScore(),
                round.getStatus(),
                RangeDict.roundStatusName(round.getStatus()));
    }

    private CertDtos.LogItem toLogItem(CertLog entry) {
        return new CertDtos.LogItem(
                entry.getAction(),
                RangeDict.certActionName(entry.getAction()),
                entry.getOperator(),
                entry.getRole(),
                RangeDict.certRoleName(entry.getRole()),
                entry.getDetail(),
                entry.getCreatedAt());
    }

    private CertDtos.CertItem toCertItem(CertApplication app, LocalDateTime now) {
        String display = displayStatus(app, now);
        CertEval latest = evalRepository.findByApplicationIdAndEvalSeq(app.getId(), app.getEvalSeq()).orElse(null);
        String ruleCode = latest == null ? "" : latest.getRuleCode();
        Integer ruleVersion = latest == null ? null : latest.getRuleVersion();
        return new CertDtos.CertItem(
                app.getId(),
                app.getAppNo(),
                app.getBowType(),
                RangeDict.bowTypeName(app.getBowType()),
                app.getDistance(),
                RangeDict.bowTypeName(app.getBowType()) + " · " + app.getDistance()
                        + " 米及以内射距（当前状态：" + RangeDict.certStatusName(display) + "）",
                ruleCode,
                ruleVersion,
                app.getValidFrom(),
                app.getValidUntil());
    }

    private static CertDtos.RuleView toRuleView(CertRule rule) {
        return new CertDtos.RuleView(
                rule.getId(),
                rule.getRuleCode(),
                rule.getVersion(),
                rule.getBowType(),
                RangeDict.bowTypeName(rule.getBowType()),
                rule.getDistance(),
                rule.getGroupSize(),
                rule.getMinRounds(),
                rule.getMinAverage(),
                rule.getValidityMonths(),
                rule.getStatus(),
                RangeDict.certRuleStatusName(rule.getStatus()),
                rule.getNote(),
                rule.getCreatedBy(),
                rule.getCreatedAt());
    }

    private static String requireName(String name, String who) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("请填写" + who + "姓名");
        }
        if (trimmed.length() > 32) {
            throw new BizException(who + "姓名过长");
        }
        return trimmed;
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String trimOrNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fmtTime(java.time.LocalDateTime time) {
        return time == null ? "" : time.format(TXT_TIME);
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
