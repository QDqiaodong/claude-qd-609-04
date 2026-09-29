package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Member;
import com.archery.range.domain.MemberMergeLog;
import com.archery.range.domain.MemberMergePreview;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.dto.MergeDtos;
import com.archery.range.repository.MemberMergeLogRepository;
import com.archery.range.repository.MemberMergePreviewRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 重复会员档案合并。
 *
 * 流程与保证：
 * <ol>
 *   <li>值班经理选保留档案 + 来源档案 → {@link #preview}：两侧余额、累计消费、等级、课程报名、
 *       未结束回合、历史成绩、当前器材租借、认证申请与有效认证分别列出，明确给出互斥项与处理结果，
 *       生成带业务数据指纹的预演版本；</li>
 *   <li>{@link #confirmMerge} 必须以预演版本为依据：悲观锁住两张会员卡后重算指纹，
 *       预演后任一侧余额 / 报名 / 回合 / 租借 / 认证发生变化都拒绝并要求重新预演；</li>
 *   <li>预演行原子消费（PREVIEWED → CONSUMED）：两个操作端同时确认同一来源档案只成功一次，
 *       失败者看到「档案已被处理」与合并去向；</li>
 *   <li>全部业务关系在同一事务内迁移（{@link MergeRelationMigrator}），任一步失败整体回滚，
 *       来源档案仍保持可用；成功后按保留会员重新核对个人最好分组，再把来源档案封存；</li>
 *   <li>不删除会员、不改手机号：原卡号、原姓名、合并操作者、原因与时间写入合并轨迹永久可查。</li>
 * </ol>
 */
@Service
public class MergeService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MemberRepository memberRepository;
    private final MemberMergePreviewRepository previewRepository;
    private final MemberMergeLogRepository logRepository;
    private final RoundRepository roundRepository;
    private final com.archery.range.repository.EquipmentRepository equipmentRepository;
    private final com.archery.range.repository.LaneRepository laneRepository;
    private final MergeRelationMigrator migrator;
    private final ObjectMapper objectMapper;

    private final MergeAnalysis analysis;

    public MergeService(MemberRepository memberRepository,
            MemberMergePreviewRepository previewRepository,
            MemberMergeLogRepository logRepository,
            RoundRepository roundRepository,
            com.archery.range.repository.EquipmentRepository equipmentRepository,
            com.archery.range.repository.LaneRepository laneRepository,
            MergeRelationMigrator migrator,
            ObjectMapper objectMapper,
            com.archery.range.repository.CourseEnrollRepository enrollRepository,
            com.archery.range.repository.CourseRepository courseRepository,
            com.archery.range.repository.MatchArrowRepository matchArrowRepository,
            com.archery.range.repository.ShootoffArrowRepository shootoffArrowRepository,
            com.archery.range.repository.EquipmentRentShadowRepository equipmentShadowRepository,
            com.archery.range.repository.CertApplicationRepository certApplicationRepository,
            com.archery.range.repository.TournamentTeamMemberRepository teamMemberRepository,
            com.archery.range.repository.TournamentTeamRepository teamRepository,
            com.archery.range.repository.TournamentRepository tournamentRepository) {
        this.memberRepository = memberRepository;
        this.previewRepository = previewRepository;
        this.logRepository = logRepository;
        this.roundRepository = roundRepository;
        this.equipmentRepository = equipmentRepository;
        this.laneRepository = laneRepository;
        this.migrator = migrator;
        this.objectMapper = objectMapper;
        this.analysis = new MergeAnalysis(roundRepository, enrollRepository, courseRepository,
                equipmentRepository, equipmentShadowRepository, laneRepository, certApplicationRepository,
                teamMemberRepository, teamRepository, tournamentRepository,
                matchArrowRepository, shootoffArrowRepository);
    }

    // ---------------- 预演 ----------------

    @Transactional
    public MergeDtos.MergePreview preview(MergeDtos.MergePreviewReq req) {
        String operator = requireName(req.operator(), "值班经理");
        String reason = requireReason(req.reason());
        if (req.keepMemberId() != null && req.keepMemberId().equals(req.sourceMemberId())) {
            throw new BizException("保留档案与来源档案不能是同一张卡");
        }
        Member keep = requireActive(req.keepMemberId(), "保留档案");
        Member source = requireActive(req.sourceMemberId(), "来源档案");

        MergeAnalysis.Report report = analysis.analyze(keep, source);

        // 同一对会员的旧待确认预演一律作废：确认只能依据最新预演版本
        previewRepository.supersedeOldPreviews(keep.getId(), source.getId());

        MemberMergePreview preview = new MemberMergePreview();
        preview.setPreviewNo(nextPreviewNo());
        preview.setKeepMemberId(keep.getId());
        preview.setSourceMemberId(source.getId());
        preview.setOperator(operator);
        preview.setReason(reason);
        preview.setStatus(MemberMergePreview.STATUS_PREVIEWED);
        preview.setKeepFingerprint(report.keep().fingerprint());
        preview.setSourceFingerprint(report.source().fingerprint());
        preview.setSnapshotJson(writeJson(toSnapshot(report)));
        preview.setCreatedAt(LocalDateTime.now());
        preview = previewRepository.save(preview);
        previewRepository.flush();

        return toPreviewView(preview, report, operator, reason);
    }

    // ---------------- 确认合并 ----------------

    @Transactional
    public MergeDtos.MergeResult confirmMerge(MergeDtos.MergeConfirmReq req) {
        String operator = requireName(req.operator(), "值班经理");
        if (req.previewId() == null) {
            throw new BizException("缺少预演版本，请先发起合并预演");
        }

        // 锁预演行：两个操作端并发确认在此串行，只有一端能原子消费成功
        MemberMergePreview preview = previewRepository.findByIdForUpdate(req.previewId())
                .orElseThrow(() -> new BizException("合并预演不存在，请重新发起预演"));

        if (MemberMergePreview.STATUS_CONSUMED.equals(preview.getStatus())) {
            throw alreadyHandled(preview);
        }
        if (MemberMergePreview.STATUS_SUPERSEDED.equals(preview.getStatus())) {
            throw new BizException("该预演版本已被更新的预演取代，请以最新预演为准重新确认");
        }

        // 固定加锁顺序（保留卡 id 小的先锁），与认证通过等同样锁会员行的流程避免死锁
        Long keepId = preview.getKeepMemberId();
        Long sourceId = preview.getSourceMemberId();
        Long firstId = Math.min(keepId, sourceId);
        Long secondId = Math.max(keepId, sourceId);
        memberRepository.findByIdForUpdate(firstId).orElseThrow();
        memberRepository.findByIdForUpdate(secondId).orElseThrow();

        Member keep = memberRepository.findById(keepId)
                .orElseThrow(() -> new BizException("保留档案不存在：id=" + keepId));
        Member source = memberRepository.findById(sourceId)
                .orElseThrow(() -> new BizException("来源档案不存在：id=" + sourceId));

        // 来源档案可能已被另一次合并封存：明确告知去向，绝不重复迁移
        if (Member.STATUS_MERGED.equals(source.getStatus())) {
            throw new BizException("来源档案 [" + source.getCardNo() + "] 已于 "
                    + fmt(source.getMergedAt()) + " 被值班经理 " + nullToEmpty(source.getMergedBy())
                    + " 合并到保留档案 [" + labelOf(source.getMergedIntoId())
                    + "]，不能再次合并，请刷新查看保留档案");
        }
        if (Member.STATUS_MERGED.equals(keep.getStatus())) {
            throw new BizException("保留档案 [" + keep.getCardNo() + "] 已被封存，不能作为合并保留方，请重新选择");
        }

        // 以预演版本为依据：重算两侧指纹，任一变化都拒绝确认
        // 先锁住两侧当前租借器材与占用箭道行，与租借 / 归还 / 开台 / 收台在确认窗口内互斥
        equipmentRepository.findRentedByRenterForUpdate(keepId);
        equipmentRepository.findRentedByRenterForUpdate(sourceId);
        laneRepository.findOccupiedByMemberForUpdate(keepId);
        laneRepository.findOccupiedByMemberForUpdate(sourceId);

        MergeAnalysis.Report current = analysis.analyze(keep, source);
        if (!current.keep().fingerprint().equals(preview.getKeepFingerprint())
                || !current.source().fingerprint().equals(preview.getSourceFingerprint())) {
            throw new BizException("预演之后会员业务数据已发生变化（余额 / 报名 / 回合 / 租借 / 认证等），"
                    + "本次确认不能以旧预演为依据，请重新发起预演后再确认");
        }
        if (!current.mergeable()) {
            throw new BizException("当前存在互斥项，不能合并："
                    + current.conflicts().stream().map(MergeDtos.ConflictItem::detail)
                            .reduce((a, b) -> a + "；" + b).orElse(""));
        }

        // 原子消费预演：并发的另一端返回 0
        int consumed = previewRepository.consumeIfPreviewed(preview.getId(), operator, LocalDateTime.now());
        if (consumed == 0) {
            throw alreadyHandled(preview);
        }
        preview.setStatus(MemberMergePreview.STATUS_CONSUMED);
        preview.setConsumedBy(operator);
        preview.setConsumedAt(LocalDateTime.now());

        // ---------- 金额合并：余额相加、累计消费相加、等级按合计重算且不降级 ----------
        BigDecimal keepBalanceBefore = keep.getBalance();
        BigDecimal sourceBalanceBefore = source.getBalance();
        BigDecimal keepSpendBefore = keep.getTotalSpend();
        BigDecimal sourceSpendBefore = source.getTotalSpend();
        String levelBefore = keep.getLevel();

        BigDecimal balanceAfter = keepBalanceBefore.add(sourceBalanceBefore).setScale(2, RoundingMode.HALF_UP);
        BigDecimal spendAfter = keepSpendBefore.add(sourceSpendBefore).setScale(2, RoundingMode.HALF_UP);
        String recalculated = RangeDict.levelByTotalSpend(spendAfter);
        String finalLevel = maxLevel(recalculated, keep.getLevel(), source.getLevel());

        List<Long> duplicateEnrollIds = current.resolves().stream()
                .map(MergeDtos.ResolveItem::droppedEnrollId)
                .toList();

        // ---------- 全部业务关系迁移（任一步抛异常整体回滚，来源档案仍可用） ----------
        MergeRelationMigrator.MigrationResult migration =
                migrator.migrate(keepId, keep.getName(), sourceId, duplicateEnrollIds);

        // ---------- 迁移后按保留会员重新核对个人最好分组（只动 personal_best 标记） ----------
        int bestRemarked = regroupPersonalBests(keepId);

        // ---------- 保留卡落合并后金额 / 等级 ----------
        keep.setBalance(balanceAfter);
        keep.setTotalSpend(spendAfter);
        keep.setLevel(finalLevel);
        memberRepository.save(keep);

        // ---------- 来源档案封存：余额清零（已转入保留卡），原卡号 / 原姓名 / 原手机号保留 ----------
        LocalDateTime mergedAt = LocalDateTime.now();
        source.setStatus(Member.STATUS_MERGED);
        source.setMergedIntoId(keepId);
        source.setMergedAt(mergedAt);
        source.setMergedBy(operator);
        source.setMergeReason(preview.getReason());
        source.setBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        memberRepository.save(source);

        // ---------- 合并轨迹 ----------
        List<MergeDtos.TrailDetailItem> details = new ArrayList<>(migration.details());
        details.add(new MergeDtos.TrailDetailItem("FINANCE", "余额与累计消费",
                "MERGE", "余额 " + keepBalanceBefore.toPlainString() + " + " + sourceBalanceBefore.toPlainString()
                        + " = " + balanceAfter.toPlainString() + "；累计消费 "
                        + keepSpendBefore.toPlainString() + " + " + sourceSpendBefore.toPlainString()
                        + " = " + spendAfter.toPlainString()));
        details.add(new MergeDtos.TrailDetailItem("LEVEL", "会员等级",
                "RECALC", "按合并后累计消费重算为「" + RangeDict.memberLevelName(recalculated)
                        + "」，最终等级「" + RangeDict.memberLevelName(finalLevel) + "」（只升不降）"));
        if (bestRemarked > 0) {
            details.add(new MergeDtos.TrailDetailItem("PERSONAL_BEST", "个人最好重新归组",
                    "REMARK", "迁移后按保留会员重新核对个人最好分组，重标 " + bestRemarked
                            + " 个回合（箭值与完成时间未改写）"));
        }
        details.add(new MergeDtos.TrailDetailItem("ARCHIVE", "来源档案封存",
                "ARCHIVE", "来源卡 " + source.getCardNo() + "（" + source.getName()
                        + "）封存为历史档案，不可再充值 / 开台 / 报名 / 租借 / 开始新回合；旧卡入口跳转保留卡 "
                        + keep.getCardNo()));

        MemberMergeLog log = new MemberMergeLog();
        log.setMergeNo(nextMergeNo());
        log.setPreviewId(preview.getId());
        log.setKeepMemberId(keepId);
        log.setSourceMemberId(sourceId);
        log.setKeepCardNo(keep.getCardNo());
        log.setSourceCardNo(source.getCardNo());
        log.setKeepName(keep.getName());
        log.setSourceName(source.getName());
        log.setKeepBalanceBefore(keepBalanceBefore);
        log.setSourceBalanceBefore(sourceBalanceBefore);
        log.setKeepSpendBefore(keepSpendBefore);
        log.setSourceSpendBefore(sourceSpendBefore);
        log.setBalanceAfter(balanceAfter);
        log.setTotalSpendAfter(spendAfter);
        log.setLevelBefore(levelBefore);
        log.setLevelAfter(finalLevel);
        log.setRoundsMoved(migration.roundsMoved());
        log.setEnrollmentsMoved(migration.enrollmentsMoved());
        log.setEnrollmentsDropped(migration.enrollmentsDropped());
        log.setEquipmentMoved(migration.equipmentMoved());
        log.setLanesMoved(migration.lanesMoved());
        log.setCertsMoved(migration.certsMoved());
        log.setTournamentMoved(migration.tournamentMoved());
        log.setDetailJson(writeJson(details));
        log.setReason(preview.getReason());
        log.setOperator(operator);
        log.setMergedAt(mergedAt);
        log = logRepository.save(log);

        MergeDtos.MigrationCounts counts = new MergeDtos.MigrationCounts(
                migration.roundsMoved(), migration.enrollmentsMoved(), migration.enrollmentsDropped(),
                migration.equipmentMoved(), migration.lanesMoved(), migration.certsMoved(),
                migration.tournamentMoved());
        return new MergeDtos.MergeResult(
                log.getId(),
                log.getMergeNo(),
                mergedAt,
                operator,
                keepId,
                keep.getCardNo(),
                sourceId,
                source.getCardNo(),
                balanceAfter,
                spendAfter,
                finalLevel,
                RangeDict.memberLevelName(finalLevel),
                counts,
                toPreviewView(preview, current, operator, preview.getReason()));
    }

    // ---------------- 轨迹查询 / 旧卡入口 ----------------

    /** 与某会员相关的合并轨迹（作为保留方或来源方）；会员本人只能查看自己的结果 */
    @Transactional(readOnly = true)
    public List<MergeDtos.MergeTrailView> trails(Long memberId) {
        if (memberId == null || memberRepository.findById(memberId).isEmpty()) {
            throw new BizException("会员不存在：id=" + memberId);
        }
        return logRepository.findByKeepMemberIdOrSourceMemberIdOrderByMergedAtDescIdDesc(memberId, memberId)
                .stream().map(this::toTrailView).toList();
    }

    /**
     * 旧卡号入口：按卡号查会员。来源档案封存后返回跳转信息（保留档案 + 合并时间 + 原因 + 操作者）；
     * 在用卡直接返回本人（target 即本人）。
     */
    @Transactional(readOnly = true)
    public MergeDtos.ArchivedRedirect lookupByCardNo(String cardNo) {
        String no = cardNo == null ? "" : cardNo.trim();
        if (no.isEmpty()) {
            throw new BizException("请输入要查询的会员卡号");
        }
        Member member = memberRepository.findByCardNo(no)
                .orElseThrow(() -> new BizException("会员卡号不存在：" + no));
        if (Member.STATUS_ACTIVE.equals(member.getStatus())) {
            return new MergeDtos.ArchivedRedirect(
                    member.getId(), member.getCardNo(), member.getName(),
                    Member.STATUS_ACTIVE,
                    member.getId(), member.getCardNo(), member.getName(),
                    null, null, null);
        }
        Long targetId = member.getMergedIntoId();
        Member target = targetId == null ? null : memberRepository.findById(targetId).orElse(null);
        if (target == null) {
            throw new BizException("来源档案 [" + no + "] 的保留档案已缺失，请联系管理员核对合并轨迹");
        }
        return new MergeDtos.ArchivedRedirect(
                member.getId(), member.getCardNo(), member.getName(),
                Member.STATUS_MERGED,
                target.getId(), target.getCardNo(), target.getName(),
                member.getMergedAt(), member.getMergeReason(), member.getMergedBy());
    }

    // ---------------- 个人最好重新归组（只改 personal_best 标记） ----------------

    /**
     * 关系迁移后按保留会员重新核对个人最好分组：
     * 取保留会员名下全部已提交回合，按 {@link PersonalBestSupport} 的可比组别分组，
     * 每组只保留唯一最好（总分 → 完成时间 → 回合 id 的全序），只更新 personal_best 标记，
     * 绝不改写箭值、完成时间或认证规则快照。返回标记被改动的回合数。
     */
    private int regroupPersonalBests(Long keepMemberId) {
        List<Round> rounds = roundRepository.findByMemberIdAndStatusOrderByStartTimeDesc(keepMemberId, "SUBMITTED");
        Map<PersonalBestSupport.GroupKey, List<Round>> groups = new HashMap<>();
        for (Round round : rounds) {
            PersonalBestSupport.Eligibility check = PersonalBestSupport.check(round);
            if (check.eligible()) {
                groups.computeIfAbsent(check.key(), key -> new ArrayList<>()).add(round);
            }
        }
        List<Round> touched = new ArrayList<>();
        int changed = 0;
        for (List<Round> sameGroup : groups.values()) {
            changed += PersonalBestSupport.applyBestMarks(sameGroup);
            touched.addAll(sameGroup);
        }
        if (!touched.isEmpty()) {
            roundRepository.saveAll(touched);
            roundRepository.flush();
        }
        return changed;
    }

    // ---------------- 工具 ----------------

    private Member requireActive(Long id, String role) {
        if (id == null) {
            throw new BizException("请选择" + role);
        }
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：id=" + id));
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException(role + " [" + member.getCardNo() + "] 已是已合并封存的历史档案"
                    + (member.getMergedIntoId() == null ? "" : "（已并入 " + labelOf(member.getMergedIntoId()) + "）")
                    + "，不能再参与合并");
        }
        return member;
    }

    private BizException alreadyHandled(MemberMergePreview preview) {
        Member source = memberRepository.findById(preview.getSourceMemberId()).orElse(null);
        Member keep = memberRepository.findById(preview.getKeepMemberId()).orElse(null);
        String target = "";
        String mergedAt = "";
        if (source != null && Member.STATUS_MERGED.equals(source.getStatus()) && source.getMergedIntoId() != null) {
            Member actualTarget = memberRepository.findById(source.getMergedIntoId()).orElse(keep);
            if (actualTarget != null) {
                target = "，档案已被合并到保留卡 " + actualTarget.getCardNo() + "（" + actualTarget.getName() + "）";
                mergedAt = "，合并时间 " + fmt(source.getMergedAt());
            }
        }
        return new BizException("该来源档案已被处理（预演版本已消费）" + target + mergedAt
                + "，不能重复合并，余额未重复累加、业务关系未重复迁移");
    }

    private String labelOf(Long memberId) {
        if (memberId == null) {
            return "";
        }
        return memberRepository.findById(memberId)
                .map(m -> m.getCardNo() + "（" + m.getName() + "）").orElse("id=" + memberId);
    }

    private static String maxLevel(String... levels) {
        String best = "NORMAL";
        for (String level : levels) {
            if (RangeDict.memberLevelRank(level) > RangeDict.memberLevelRank(best)) {
                best = level;
            }
        }
        return best;
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

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("请填写合并原因（如：同一会员被误开两张卡）");
        }
        if (trimmed.length() > 500) {
            throw new BizException("合并原因不超过 500 字");
        }
        return trimmed;
    }

    private String nextPreviewNo() {
        String no = "MP" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (previewRepository.existsByPreviewNo(no)) {
            no = "MP" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private String nextMergeNo() {
        String no = "MG" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (logRepository.existsByMergeNo(no)) {
            no = "MG" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private static String fmt(LocalDateTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("合并快照序列化失败：" + e.getMessage());
        }
    }

    /** 存入预演的快照：确认后页面仍可展示当时的两侧画像与处理结论 */
    private Map<String, Object> toSnapshot(MergeAnalysis.Report report) {
        Map<String, Object> map = new HashMap<>();
        map.put("keep", report.keep().view());
        map.put("source", report.source().view());
        map.put("conflicts", report.conflicts());
        map.put("resolves", report.resolves());
        map.put("finance", report.finance());
        map.put("migration", report.migration());
        map.put("mergeable", report.mergeable());
        return map;
    }

    private MergeDtos.MergePreview toPreviewView(MemberMergePreview preview,
            MergeAnalysis.Report report, String operator, String reason) {
        return new MergeDtos.MergePreview(
                preview.getId(),
                preview.getPreviewNo(),
                preview.getStatus(),
                operator,
                reason,
                preview.getCreatedAt(),
                report.keep().view(),
                report.source().view(),
                report.conflicts(),
                report.mergeable(),
                report.resolves(),
                report.finance(),
                report.migration());
    }

    private MergeDtos.MergeTrailView toTrailView(MemberMergeLog log) {
        List<MergeDtos.TrailDetailItem> details = new ArrayList<>();
        if (log.getDetailJson() != null && !log.getDetailJson().isBlank()) {
            try {
                details = objectMapper.readValue(log.getDetailJson(), new TypeReference<>() {
                });
            } catch (Exception ignored) {
                // 明细解析失败不影响轨迹主体展示
                details = new ArrayList<>();
            }
        }
        details.sort(Comparator.comparing(MergeDtos.TrailDetailItem::category));
        return new MergeDtos.MergeTrailView(
                log.getId(),
                log.getMergeNo(),
                log.getKeepMemberId(),
                log.getKeepCardNo(),
                log.getKeepName(),
                log.getSourceMemberId(),
                log.getSourceCardNo(),
                log.getSourceName(),
                log.getKeepBalanceBefore(),
                log.getSourceBalanceBefore(),
                log.getBalanceAfter(),
                log.getTotalSpendAfter(),
                log.getLevelBefore(),
                RangeDict.memberLevelName(log.getLevelBefore()),
                log.getLevelAfter(),
                RangeDict.memberLevelName(log.getLevelAfter()),
                log.getRoundsMoved(),
                log.getEnrollmentsMoved(),
                log.getEnrollmentsDropped(),
                log.getEquipmentMoved(),
                log.getLanesMoved(),
                log.getCertsMoved(),
                log.getTournamentMoved(),
                log.getReason(),
                log.getOperator(),
                log.getMergedAt(),
                details);
    }

    // 控制器查看预演详情：基于预演行对应的两张卡当前状态重算结论（确认仍严格以指纹版本为依据）
    @Transactional(readOnly = true)
    public MergeDtos.MergePreview getPreview(Long previewId) {
        MemberMergePreview preview = previewRepository.findById(previewId)
                .orElseThrow(() -> new BizException("合并预演不存在：id=" + previewId));
        Member keep = memberRepository.findById(preview.getKeepMemberId()).orElseThrow();
        Member source = memberRepository.findById(preview.getSourceMemberId()).orElseThrow();
        MergeAnalysis.Report report = analysis.analyze(keep, source);
        return toPreviewView(preview, report, preview.getOperator(), preview.getReason());
    }
}
