package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.archery.range.common.BizException;
import com.archery.range.common.ForbiddenException;
import com.archery.range.domain.CertApplication;
import com.archery.range.domain.CourseEnroll;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.MemberMerge;
import com.archery.range.domain.MemberMergeLog;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.domain.ShootoffArrow;
import com.archery.range.domain.TournamentTeamMember;
import com.archery.range.dto.MemberMergeDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.CourseEnrollRepository;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MatchArrowRepository;
import com.archery.range.repository.MemberMergeLogRepository;
import com.archery.range.repository.MemberMergeRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;
import com.archery.range.repository.ShootoffArrowRepository;
import com.archery.range.repository.TournamentTeamMemberRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 重复会员档案合并。
 *
 * 流程：值班经理选择保留档案 + 来源档案 → 预演（逐项列出两张卡数据、互斥项与合并结果，
 * 固化双方数据指纹与完整快照）→ 确认（以预演版本为唯一依据，指纹不符即拒绝并要求重新预演）。
 *
 * 合并口径：
 * - 余额、累计消费合并；会员等级按合并后累计消费重算，且不能低于保留档案当前等级。
 * - 同一课程重复报名只保留报名较早的一条，另一条删除（不留重复关系）。
 * - 两张卡同时存在未结束计分回合、租出同一件器材、或同一弓种且适用范围重叠的有效认证 → 互斥阻止。
 * - 已完成回合、认证历史、个人最好标记全部保留原始内容迁移；箭值、完成时间、认证规则快照绝不改写。
 *   迁移后按保留会员在每个可比组别（弓种 + 射距 + 箭数）重新核对唯一个人最好。
 * - 成功后来源档案封存（MERGED）：原卡号 / 原姓名 / 手机号保留，余额清零，记录合并时间、操作经理与原因，
 *   不能再充值 / 开台 / 报名 / 租借 / 开始新回合；从旧卡号进入时跳转保留档案。
 *
 * 一致性：确认在单事务内完成，先锁双方会员行与全部相关业务行；任一步失败整体回滚，
 * 来源档案仍保持可用。状态条件更新 + 来源行锁保证两个操作端并发确认同一来源只能成功一次。
 */
@Service
public class MemberMergeService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> OPEN_ROUND_STATUS = Set.of("ONGOING", "PAUSED");
    private static final Set<String> OPEN_CERT_STATUS = Set.of("PENDING", "NEED_MORE");

    private final MemberRepository memberRepository;
    private final MemberMergeRepository mergeRepository;
    private final MemberMergeLogRepository mergeLogRepository;
    private final RoundRepository roundRepository;
    private final CourseEnrollRepository enrollRepository;
    private final CourseRepository courseRepository;
    private final EquipmentRepository equipmentRepository;
    private final LaneRepository laneRepository;
    private final CertApplicationRepository certApplicationRepository;
    private final TournamentTeamMemberRepository teamMemberRepository;
    private final MatchArrowRepository matchArrowRepository;
    private final ShootoffArrowRepository shootoffArrowRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTransaction;

    public MemberMergeService(MemberRepository memberRepository,
            MemberMergeRepository mergeRepository,
            MemberMergeLogRepository mergeLogRepository,
            RoundRepository roundRepository,
            CourseEnrollRepository enrollRepository,
            CourseRepository courseRepository,
            EquipmentRepository equipmentRepository,
            LaneRepository laneRepository,
            CertApplicationRepository certApplicationRepository,
            TournamentTeamMemberRepository teamMemberRepository,
            MatchArrowRepository matchArrowRepository,
            ShootoffArrowRepository shootoffArrowRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.memberRepository = memberRepository;
        this.mergeRepository = mergeRepository;
        this.mergeLogRepository = mergeLogRepository;
        this.roundRepository = roundRepository;
        this.enrollRepository = enrollRepository;
        this.courseRepository = courseRepository;
        this.equipmentRepository = equipmentRepository;
        this.laneRepository = laneRepository;
        this.certApplicationRepository = certApplicationRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.matchArrowRepository = matchArrowRepository;
        this.shootoffArrowRepository = shootoffArrowRepository;
        this.objectMapper = objectMapper;
        // 预演失效（STALE）必须独立落库：确认事务因指纹不符回滚时，失效标记仍要留下可查
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ================= 预演 =================

    @Transactional
    public MemberMergeDtos.MergePreviewView preview(MemberMergeDtos.MergePreviewReq req) {
        requireManager(req.role(), req.operator());
        String reason = requireReason(req.reason());
        Long keepId = req.keepMemberId();
        Long sourceId = req.sourceMemberId();
        if (keepId.equals(sourceId)) {
            throw new BizException("保留档案与来源档案不能是同一张卡");
        }
        Member keep = requireMember(keepId);
        Member source = requireMember(sourceId);
        requireUsable(keep, "保留档案");
        requireUsable(source, "来源档案");

        LocalDateTime now = LocalDateTime.now();
        MemberMergeDtos.MergeSide keepSide = buildSide(keep);
        MemberMergeDtos.MergeSide sourceSide = buildSide(source);
        List<MemberMergeDtos.ConflictItem> conflicts = detectConflicts(keepSide, sourceSide);
        MemberMergeDtos.MergePlan plan = buildPlan(keep, source, keepSide, sourceSide, conflicts.isEmpty(), now);

        MemberMergeDtos.MergePreviewView view = new MemberMergeDtos.MergePreviewView(
                null, null, MemberMerge.STATUS_PREVIEWED, reason, req.operator().trim(), now,
                keepSide, sourceSide, conflicts, conflicts.isEmpty(), plan);

        // 同一对档案再次预演：旧的待确认预演作废（SUPERSEDED），只能拿最新预演去确认
        List<MemberMerge> previous = mergeRepository
                .findByKeepMemberIdOrSourceMemberIdOrderByIdDesc(keepId, sourceId).stream()
                .filter(m -> MemberMerge.STATUS_PREVIEWED.equals(m.getStatus()))
                .filter(m -> Objects.equals(m.getKeepMemberId(), keepId)
                        && Objects.equals(m.getSourceMemberId(), sourceId))
                .toList();
        for (MemberMerge old : previous) {
            old.setStatus(MemberMerge.STATUS_SUPERSEDED);
            mergeRepository.save(old);
            log(old.getId(), MemberMergeLog.ACTION_SUPERSEDE, "系统",
                    "保留/来源档案重新发起预演，本预演单作废，请以最新预演为准", now);
        }

        MemberMerge merge = new MemberMerge();
        merge.setMergeNo(nextMergeNo());
        merge.setKeepMemberId(keepId);
        merge.setSourceMemberId(sourceId);
        merge.setReason(reason);
        merge.setStatus(MemberMerge.STATUS_PREVIEWED);
        merge.setPreviewOperator(req.operator().trim());
        merge.setPreviewedAt(now);
        merge.setKeepFingerprint(fingerprint(keepSide));
        merge.setSourceFingerprint(fingerprint(sourceSide));
        merge.setSnapshot(writeJson(view));
        merge.setConflicts(writeJson(conflicts));
        merge = mergeRepository.save(merge);

        log(merge.getId(), MemberMergeLog.ACTION_PREVIEW, req.operator().trim(),
                "合并预演：保留 " + keep.getCardNo() + "（" + keep.getName() + "），来源 "
                        + source.getCardNo() + "（" + source.getName() + "）；互斥项 "
                        + conflicts.size() + " 个" + (conflicts.isEmpty() ? "，可确认合并" : "，暂不能合并"),
                now);

        return new MemberMergeDtos.MergePreviewView(
                merge.getId(), merge.getMergeNo(), merge.getStatus(), reason, merge.getPreviewOperator(), now,
                keepSide, sourceSide, conflicts, conflicts.isEmpty(), plan);
    }

    // ================= 确认（以预演版本为依据） =================

    @Transactional
    public MemberMergeDtos.MergeDetailView confirm(MemberMergeDtos.MergeConfirmReq req) {
        requireManager(req.role(), req.operator());

        // 先读预演单（不加行锁），完成所有「拒绝类」校验（状态 / 封存 / 指纹 / 互斥）。
        // 这类校验必须能在不持有 merge 行锁的情况下抛业务异常并回滚，否则 H2 下 REQUIRES_NEW
        // 的 STALE 落库会被外层行锁挡住；真正的并发唯一成功靠后面的条件更新 + 会员行锁保证。
        MemberMerge merge = mergeRepository.findById(req.mergeId())
                .orElseThrow(() -> new BizException("合并预演单不存在：id=" + req.mergeId()));
        List<Long> memberIds = List.of(merge.getKeepMemberId(), merge.getSourceMemberId())
                .stream().sorted().toList();
        Member first = memberRepository.findByIdForUpdate(memberIds.get(0))
                .orElseThrow(() -> new BizException("会员不存在：id=" + memberIds.get(0)));
        Member second = memberRepository.findByIdForUpdate(memberIds.get(1))
                .orElseThrow(() -> new BizException("会员不存在：id=" + memberIds.get(1)));
        Member keep = merge.getKeepMemberId().equals(first.getId()) ? first : second;
        Member source = merge.getSourceMemberId().equals(first.getId()) ? first : second;

        if (MemberMerge.STATUS_CONFIRMED.equals(merge.getStatus())) {
            throw new BizException("来源档案 [" + source.getCardNo() + "]（" + source.getName()
                    + "）已被 " + merge.getConfirmedBy() + " 于 " + fmt(merge.getConfirmedAt())
                    + " 合并到保留档案 [" + keep.getCardNo() + "]，不能重复合并（余额与业务关系不会重复迁移）");
        }
        if (MemberMerge.STATUS_SUPERSEDED.equals(merge.getStatus())) {
            throw new BizException("该预演已被更新的预演取代，请以最新预演为准重新确认");
        }
        if (!MemberMerge.STATUS_PREVIEWED.equals(merge.getStatus())) {
            throw new BizException("该预演单当前状态不可确认，请重新发起预演");
        }
        requireUsable(keep, "保留档案");

        // 来源已被别的合并单处理（并发 / 其他操作端）：明确告知档案已被处理
        if (Member.STATUS_MERGED.equals(source.getStatus())) {
            markStale(merge, "来源档案已在其他合并操作中封存");
            throw new BizException("来源档案 [" + source.getCardNo() + "]（" + source.getName()
                    + "）已被处理（封存至档案 id=" + source.getMergedIntoId() + "），不能重复合并");
        }

        // 以预演版本为依据：重新计算双方当前指纹，与预演固化指纹比对
        MemberMergeDtos.MergeSide currentKeep = buildSide(keep);
        MemberMergeDtos.MergeSide currentSource = buildSide(source);
        String keepFp = fingerprint(currentKeep);
        String sourceFp = fingerprint(currentSource);
        List<String> keepChanges = diffFingerprint(merge.getKeepFingerprint(), keepFp);
        List<String> sourceChanges = diffFingerprint(merge.getSourceFingerprint(), sourceFp);
        if (!keepChanges.isEmpty() || !sourceChanges.isEmpty()) {
            List<String> all = new ArrayList<>();
            if (!keepChanges.isEmpty()) {
                all.add("保留档案 [" + keep.getCardNo() + "]：" + String.join("、", keepChanges));
            }
            if (!sourceChanges.isEmpty()) {
                all.add("来源档案 [" + source.getCardNo() + "]：" + String.join("、", sourceChanges));
            }
            markStale(merge, String.join("；", all));
            throw new BizException("预演之后数据已发生变化（" + String.join("；", all)
                    + "），本次确认已拒绝，请重新预演后再确认");
        }

        MemberMergeDtos.MergePreviewView snapshot = readPreview(merge.getSnapshot());
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.canMerge())
                || snapshot.conflicts() == null || !snapshot.conflicts().isEmpty()) {
            markStale(merge, "预演存在互斥项，不能确认");
            throw new BizException("预演存在未解决的互斥项，不能合并，请先处理后重新预演");
        }
        MemberMergeDtos.MergePlan plan = snapshot.plan();

        // 再复核一次互斥（指纹已保证数据未变，这里为防御性校验，绝不自行挑一条覆盖）
        List<MemberMergeDtos.ConflictItem> conflicts = detectConflicts(currentKeep, currentSource);
        if (!conflicts.isEmpty()) {
            markStale(merge, "确认时复核到互斥项：" + conflicts.stream()
                    .map(c -> c.typeName() + "（" + c.target() + "）").reduce((a, b) -> a + "、" + b).orElse(""));
            throw new BizException("存在互斥项，不能合并：" + conflicts.stream()
                    .map(c -> c.typeName() + "（" + c.target() + "）").reduce((a, b) -> a + "；" + b).orElse(""));
        }

        // 所有拒绝类校验通过后，再锁合并单行并做一次状态终判（并发兜底）：
        // 两个操作端同时确认在此串行，先到者置 CONFIRMED，后到者看到终态报「已被处理」。
        MemberMerge lockedMerge = mergeRepository.findByIdForUpdate(merge.getId()).orElseThrow();
        if (!MemberMerge.STATUS_PREVIEWED.equals(lockedMerge.getStatus())) {
            throw new BizException("来源档案 [" + source.getCardNo() + "] 的该预演已被处理为「"
                    + RangeDict.mergeStatusName(lockedMerge.getStatus()) + "」，不能重复确认");
        }

        LocalDateTime now = LocalDateTime.now();
        lockBusinessRows(keep, source);

        // ---- 0. 淘汰赛关系先迁：队员身份 / 局内箭值 / 加赛箭值都带唯一约束，
        //          一旦两张卡在同一赛事同队 / 同局同人会撞键，必须在改动其他关系之前失败，整单回滚 ----
        int teamBindsMoved = teamMemberRepository.reassignMember(source.getId(), keep.getId());
        int matchArrowsMoved = matchArrowRepository.reassignMember(source.getId(), keep.getId());
        int shootoffArrowsMoved = shootoffArrowRepository.reassignMember(source.getId(), keep.getId());

        // ---- 1. 课程报名：同一课程重复报名只保留较早一条；其余迁到保留档案 ----
        List<MemberMergeDtos.DroppedEnroll> dropped = plan.duplicateEnrollments() == null
                ? List.of() : plan.duplicateEnrollments();
        for (MemberMergeDtos.DroppedEnroll dup : dropped) {
            CourseEnroll sourceEnroll = enrollRepository.findByCourseIdAndMemberId(dup.courseId(), source.getId())
                    .orElseThrow(() -> new BizException("迁移中断：来源报名记录已变化，请重新预演"));
            CourseEnroll keptEnroll = enrollRepository.findByCourseIdAndMemberId(dup.courseId(), keep.getId())
                    .orElseThrow(() -> new BizException("迁移中断：保留报名记录已变化，请重新预演"));
            if (sourceEnroll.getEnrollTime().isBefore(keptEnroll.getEnrollTime())) {
                // 数据与预演不符（较早一条在来源）→ 中断回滚，不能自行改写
                throw new BizException("迁移中断：课程 [" + dup.courseName() + "] 较早报名归属与预演不一致，请重新预演");
            }
            enrollRepository.delete(sourceEnroll);
        }
        enrollRepository.flush();
        // 其余来源报名（均不与保留卡重复）整体迁到保留档案
        List<CourseEnroll> sourceEnrolls = enrollRepository.findByMemberIdOrderByIdAsc(source.getId());
        List<CourseEnroll> toMigrateEnrolls = new ArrayList<>(sourceEnrolls);
        if (!toMigrateEnrolls.isEmpty()) {
            enrollRepository.reassignMembers(toMigrateEnrolls.stream().map(CourseEnroll::getId).toList(),
                    keep, source);
        }
        // 重算受影响课程的已报名人数（去重删除后计数必须与实际报名一致）
        java.util.Set<Long> affectedCourseIds = new java.util.TreeSet<>();
        dropped.forEach(d -> affectedCourseIds.add(d.courseId()));
        toMigrateEnrolls.forEach(e -> affectedCourseIds.add(e.getCourse().getId()));
        for (Long courseId : affectedCourseIds) {
            courseRepository.findById(courseId).ifPresent(c -> {
                c.setEnrolled((int) enrollRepository.countByCourseId(courseId));
                courseRepository.save(c);
            });
        }

        // ---- 2. 计分回合：全部迁移（箭支明细随外键保留，箭值 / 完成时间不改写） ----
        int roundsMoved = roundRepository.reassignMember(keep, source);

        // ---- 3. 认证申请：全部迁移（评定快照、证据回合、轨迹随外键保留，认证规则快照不改写） ----
        int certsMoved = certApplicationRepository.reassignMember(keep, source);

        // ---- 4. 当前器材租借：改挂保留档案（租出时间不动）；防御性复核同一件器材
        List<Equipment> rented = equipmentRepository.findByRenterId(source.getId()).stream()
                .filter(e -> "RENTED".equals(e.getStatus())).toList();
        List<Long> keepRentedIds = equipmentRepository.findByRenterId(keep.getId()).stream()
                .filter(e -> "RENTED".equals(e.getStatus())).map(Equipment::getId).toList();
        for (Equipment e : rented) {
            if (keepRentedIds.contains(e.getId())) {
                throw new BizException("迁移中断：器材 [" + e.getEquipCode() + "] 同时挂在两张卡名下（互斥项）");
            }
        }
        int rentalsMoved = equipmentRepository.reassignRenter(source.getId(), keep.getId(), keep.getName());

        // ---- 5. 开台占用的箭道：占用关系迁到保留档案（开台时间 / 预计时长不动） ----
        int lanesMoved = laneRepository.reassignOccupant(source.getId(), keep.getId(), keep.getName());

        // ---- 7. 余额 / 累计消费合并，等级按累计消费重算且不降级 ----
        BigDecimal balanceAfter = keep.getBalance().add(source.getBalance())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal spendAfter = keep.getTotalSpend().add(source.getTotalSpend())
                .setScale(2, RoundingMode.HALF_UP);
        String levelBySpend = RangeDict.levelByTotalSpend(spendAfter);
        String levelAfter = RangeDict.memberLevelRank(levelBySpend) >= RangeDict.memberLevelRank(keep.getLevel())
                ? levelBySpend : keep.getLevel();
        if (plan.balanceAfter() != null && balanceAfter.compareTo(plan.balanceAfter()) != 0) {
            throw new BizException("迁移中断：合并后余额与预演不一致，请重新预演");
        }
        if (plan.spendAfter() != null && spendAfter.compareTo(plan.spendAfter()) != 0) {
            throw new BizException("迁移中断：合并后累计消费与预演不一致，请重新预演");
        }
        if (plan.levelAfter() != null && !plan.levelAfter().equals(levelAfter)) {
            throw new BizException("迁移中断：重算等级与预演不一致，请重新预演");
        }
        keep.setBalance(balanceAfter);
        keep.setTotalSpend(spendAfter);
        keep.setLevel(levelAfter);
        memberRepository.save(keep);

        // ---- 8. 个人最好按保留会员重新核对分组（只改 personal_best 标记） ----
        List<PbChange> pbChanges = recomputePersonalBests(keep.getId());

        // ---- 9. 来源档案封存：原卡号 / 原姓名 / 手机号保留，余额清零，记录合并信息 ----
        source.setStatus(Member.STATUS_MERGED);
        source.setMergedIntoId(keep.getId());
        source.setMergedAt(now);
        source.setMergedBy(req.operator().trim());
        source.setMergeReason(lockedMerge.getReason());
        source.setBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        memberRepository.save(source);

        lockedMerge.setStatus(MemberMerge.STATUS_CONFIRMED);
        lockedMerge.setConfirmedBy(req.operator().trim());
        lockedMerge.setConfirmedAt(now);
        mergeRepository.save(lockedMerge);

        log(lockedMerge.getId(), MemberMergeLog.ACTION_CONFIRM, req.operator().trim(),
                "确认合并到 [" + keep.getCardNo() + "]（" + keep.getName() + "）：迁移回合 "
                        + roundsMoved + "（含箭支明细原样保留）、报名迁移 " + toMigrateEnrolls.size()
                        + " / 重复去重 " + dropped.size() + "、认证申请 " + certsMoved
                        + "、在租器材 " + rentalsMoved + "、占用箭道 " + lanesMoved
                        + "、赛事队员 " + teamBindsMoved + "、局内箭 " + matchArrowsMoved
                        + "、加赛箭 " + shootoffArrowsMoved + "；合并后余额 " + balanceAfter
                        + "、累计消费 " + spendAfter + "、等级 " + RangeDict.memberLevelName(levelAfter)
                        + "；个人最好重归组改动标记 " + pbChanges.size() + " 处；来源卡 ["
                        + source.getCardNo() + "] 已封存",
                now);

        return detail(lockedMerge.getId());
    }

    // ================= 查询：轨迹 / 旧卡入口 =================

    /** 合并单详情（含预演快照与轨迹） */
    @Transactional(readOnly = true)
    public MemberMergeDtos.MergeDetailView detail(Long mergeId) {
        MemberMerge merge = mergeRepository.findById(mergeId)
                .orElseThrow(() -> new BizException("合并单不存在：id=" + mergeId));
        return toDetailView(merge);
    }

    /** 某会员相关的合并单（保留卡 / 来源卡都能查到自己的合并结果） */
    @Transactional(readOnly = true)
    public List<MemberMergeDtos.MergeSummary> listByMember(Long memberId) {
        if (memberRepository.findById(memberId).isEmpty()) {
            throw new BizException("会员不存在：id=" + memberId);
        }
        return mergeRepository.findByKeepMemberIdOrSourceMemberIdOrderByIdDesc(memberId, memberId)
                .stream().map(this::toSummary).toList();
    }

    /**
     * 旧卡号入口：正常卡返回自身；已封存卡返回保留档案跳转信息（合并时间 / 操作经理 / 原因）。
     */
    @Transactional(readOnly = true)
    public MemberMergeDtos.CardEntryView entryByCardNo(String cardNo) {
        String no = cardNo == null ? "" : cardNo.trim();
        if (no.isEmpty()) {
            throw new BizException("请输入会员卡号");
        }
        Member member = memberRepository.findByCardNo(no)
                .orElseThrow(() -> new BizException("会员卡号不存在：" + no));
        if (!Member.STATUS_MERGED.equals(member.getStatus())) {
            return new MemberMergeDtos.CardEntryView(member.getId(), member.getCardNo(), member.getName(),
                    member.getStatus(), false, null, null, null, null, null, null);
        }
        Member redirect = member.getMergedIntoId() == null ? null
                : memberRepository.findById(member.getMergedIntoId()).orElse(null);
        return new MemberMergeDtos.CardEntryView(
                member.getId(), member.getCardNo(), member.getName(), member.getStatus(), true,
                redirect == null ? null : redirect.getId(),
                redirect == null ? null : redirect.getCardNo(),
                redirect == null ? null : redirect.getName(),
                member.getMergedAt(), member.getMergedBy(), member.getMergeReason());
    }

    // ================= 预演数据组装 =================

    private MemberMergeDtos.MergeSide buildSide(Member member) {
        LocalDateTime now = LocalDateTime.now();
        List<Round> rounds = roundRepository.findByMemberIdOrderByStartTimeDesc(member.getId());
        List<MemberMergeDtos.OpenRoundItem> openRounds = rounds.stream()
                .filter(r -> OPEN_ROUND_STATUS.contains(r.getStatus()))
                .map(r -> new MemberMergeDtos.OpenRoundItem(
                        r.getId(), r.getRoundNo(),
                        r.getLane() == null ? "" : r.getLane().getLaneNo(),
                        r.getLane() == null ? null : r.getLane().getDistance(),
                        r.getBowType(), RangeDict.bowTypeName(r.getBowType()),
                        r.getStartTime(), r.getStatus(), RangeDict.roundStatusName(r.getStatus())))
                .toList();
        List<MemberMergeDtos.HistoryRoundItem> historyRounds = rounds.stream()
                .filter(r -> "SUBMITTED".equals(r.getStatus()))
                .map(r -> new MemberMergeDtos.HistoryRoundItem(
                        r.getId(), r.getRoundNo(),
                        r.getLane() == null ? "" : r.getLane().getLaneNo(),
                        r.getLane() == null ? null : r.getLane().getDistance(),
                        r.getBowType(), RangeDict.bowTypeName(r.getBowType()),
                        r.getStartTime(), r.getCompletedAt(), r.getArrowCount(), r.getTotalScore(),
                        r.getAverageScore(), r.getPersonalBest()))
                .toList();

        List<MemberMergeDtos.EnrollItem> enrollments = enrollRepository
                .findByMemberIdOrderByIdAsc(member.getId()).stream()
                .map(e -> new MemberMergeDtos.EnrollItem(
                        e.getId(), e.getCourse().getId(), e.getCourse().getCourseName(),
                        e.getCourse().getClassTime(), e.getEnrollTime()))
                .toList();

        List<MemberMergeDtos.RentalItem> rentals = equipmentRepository.findByRenterId(member.getId())
                .stream()
                .filter(e -> "RENTED".equals(e.getStatus()))
                .map(e -> new MemberMergeDtos.RentalItem(e.getId(), e.getEquipCode(), e.getType(),
                        RangeDict.equipTypeName(e.getType()), e.getBrand(), e.getRentedAt()))
                .toList();

        List<MemberMergeDtos.OccupiedLaneItem> occupiedLanes = laneRepository.findByOccupantId(member.getId())
                .stream()
                .filter(l -> "OCCUPIED".equals(l.getStatus()))
                .map(l -> new MemberMergeDtos.OccupiedLaneItem(l.getId(), l.getLaneNo(), l.getDistance(),
                        l.getOpenedAt(), l.getPlannedHours()))
                .toList();

        List<CertApplication> apps = certApplicationRepository.findByMemberIdOrderByIdDesc(member.getId());
        List<MemberMergeDtos.CertAppItem> certApps = apps.stream()
                .map(a -> toCertAppItem(a, displayStatus(a, now)))
                .toList();
        List<MemberMergeDtos.CertAppItem> validCerts = apps.stream()
                .filter(a -> "APPROVED".equals(a.getStatus()) && a.getValidUntil() != null
                        && !a.getValidUntil().isBefore(now))
                .map(a -> toCertAppItem(a, "APPROVED"))
                .toList();

        return new MemberMergeDtos.MergeSide(
                member.getId(), member.getCardNo(), member.getName(), member.getPhone(),
                member.getLevel(), RangeDict.memberLevelName(member.getLevel()),
                member.getBalance(), member.getTotalSpend(), member.getRegisterDate(), member.getStatus(),
                enrollments, openRounds, historyRounds, rentals, occupiedLanes, certApps, validCerts);
    }

    private MemberMergeDtos.CertAppItem toCertAppItem(CertApplication a, String display) {
        return new MemberMergeDtos.CertAppItem(
                a.getId(), a.getAppNo(), a.getBowType(), RangeDict.bowTypeName(a.getBowType()),
                a.getDistance(), a.getStatus(), RangeDict.certStatusName(a.getStatus()),
                display, RangeDict.certStatusName(display), a.getValidFrom(), a.getValidUntil());
    }

    /**
     * 互斥项（必须明确指出，系统绝不自行挑一条覆盖）：
     * 1. 两张卡都有未结束（进行中 / 安全暂停）的计分回合；
     * 2. 同一件器材同时处于两张卡的租出名下；
     * 3. 同一弓种且适用范围重叠的有效认证（射距区间相交：较高射距认证覆盖较低射距）。
     */
    /** 供验收测试直接构造两张卡的侧视图验证互斥识别逻辑（含单租用人字段下只能由脏数据产生的器材双挂交集） */
    public static List<MemberMergeDtos.ConflictItem> detectConflictsStatic(MemberMergeDtos.MergeSide keep,
            MemberMergeDtos.MergeSide source) {
        return detectConflicts(keep, source);
    }

    private static List<MemberMergeDtos.ConflictItem> detectConflicts(MemberMergeDtos.MergeSide keep,
            MemberMergeDtos.MergeSide source) {
        List<MemberMergeDtos.ConflictItem> conflicts = new ArrayList<>();

        if (!keep.openRounds().isEmpty() && !source.openRounds().isEmpty()) {
            String keepRounds = keep.openRounds().stream()
                    .map(r -> r.roundNo() + "（" + r.statusName() + "）").reduce((a, b) -> a + "、" + b).orElse("");
            String sourceRounds = source.openRounds().stream()
                    .map(r -> r.roundNo() + "（" + r.statusName() + "）").reduce((a, b) -> a + "、" + b).orElse("");
            conflicts.add(new MemberMergeDtos.ConflictItem("ONGOING_ROUND",
                    RangeDict.mergeConflictName("ONGOING_ROUND"),
                    "保留卡 " + keepRounds + "；来源卡 " + sourceRounds,
                    "两张卡都存在未结束计分回合，请先分别提交 / 处理完毕后再合并，不能自行选择丢弃"));
        }

        // 器材租借互斥：同一件器材同时被两张卡主张租用。正常流程一件器材只有一个租用人，
        // 两侧租借列表按 equipmentId 相交只可能出现在历史脏数据 / 预演后租借关系变化中，
        // 必须按互斥拦住，绝不能在合并时静默挑一条覆盖。
        for (MemberMergeDtos.RentalItem s : source.rentals()) {
            keep.rentals().stream().filter(k -> k.equipmentId().equals(s.equipmentId())).findFirst()
                    .ifPresent(k -> conflicts.add(new MemberMergeDtos.ConflictItem("SAME_EQUIPMENT",
                            RangeDict.mergeConflictName("SAME_EQUIPMENT"),
                            s.equipCode() + "（" + s.typeName() + " " + s.brand() + "）",
                            "同一件器材同时挂在两张卡租出名下，请先归还其中一张卡的租借后再合并")));
        }

        for (MemberMergeDtos.CertAppItem s : source.validCerts()) {
            for (MemberMergeDtos.CertAppItem k : keep.validCerts()) {
                if (Objects.equals(s.bowType(), k.bowType()) && scopeOverlap(s, k)) {
                    conflicts.add(new MemberMergeDtos.ConflictItem("VALID_CERT_SCOPE",
                            RangeDict.mergeConflictName("VALID_CERT_SCOPE"),
                            RangeDict.bowTypeName(s.bowType()) + "（保留卡认证 " + k.distance()
                                    + " 米 #" + k.appNo() + "；来源卡认证 " + s.distance()
                                    + " 米 #" + s.appNo() + "）",
                            "同一弓种且适用范围重叠的两笔有效认证不能自动取舍，请先撤回 / 等待其中一笔失效后再合并"));
                }
            }
        }
        return conflicts;
    }

    /** 认证适用范围为「该弓种不高于认证射距」，两区间（0..distance）只要弓种相同必重叠 */
    private static boolean scopeOverlap(MemberMergeDtos.CertAppItem a, MemberMergeDtos.CertAppItem b) {
        return Objects.equals(a.bowType(), b.bowType())
                && a.distance() != null && b.distance() != null
                && a.distance() > 0 && b.distance() > 0;
    }

    private MemberMergeDtos.MergePlan buildPlan(Member keep, Member source,
            MemberMergeDtos.MergeSide keepSide, MemberMergeDtos.MergeSide sourceSide,
            boolean noConflict, LocalDateTime now) {
        BigDecimal balanceAfter = keep.getBalance().add(source.getBalance())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal spendAfter = keep.getTotalSpend().add(source.getTotalSpend())
                .setScale(2, RoundingMode.HALF_UP);
        String levelBySpend = RangeDict.levelByTotalSpend(spendAfter);
        String levelAfter = RangeDict.memberLevelRank(levelBySpend) >= RangeDict.memberLevelRank(keep.getLevel())
                ? levelBySpend : keep.getLevel();
        boolean downgraded = RangeDict.memberLevelRank(levelAfter) < RangeDict.memberLevelRank(keep.getLevel());

        // 课程报名去重：同一课程两条报名，保留较早一条
        Map<Long, MemberMergeDtos.EnrollItem> keepByCourse = new LinkedHashMap<>();
        keepSide.enrollments().forEach(e -> keepByCourse.put(e.courseId(), e));
        List<MemberMergeDtos.DroppedEnroll> duplicates = new ArrayList<>();
        List<MemberMergeDtos.EnrollItem> toMigrate = new ArrayList<>();
        for (MemberMergeDtos.EnrollItem src : sourceSide.enrollments()) {
            MemberMergeDtos.EnrollItem kept = keepByCourse.get(src.courseId());
            if (kept == null) {
                toMigrate.add(src);
                continue;
            }
            boolean sourceEarlier = src.enrollTime().isBefore(kept.enrollTime());
            MemberMergeDtos.EnrollItem winner = sourceEarlier ? src : kept;
            MemberMergeDtos.EnrollItem loser = sourceEarlier ? kept : src;
            String winnerCard = sourceEarlier ? source.getCardNo() : keep.getCardNo();
            String loserCard = sourceEarlier ? keep.getCardNo() : source.getCardNo();
            duplicates.add(new MemberMergeDtos.DroppedEnroll(
                    src.courseId(), src.courseName(),
                    winnerCard, winner.enrollTime(), loserCard, loser.enrollTime()));
        }

        List<MemberMergeDtos.RentalItem> rentalsToMigrate = noConflict ? sourceSide.rentals() : List.of();
        List<MemberMergeDtos.OccupiedLaneItem> lanesToMigrate = sourceSide.occupiedLanes();
        List<MemberMergeDtos.OpenRoundItem> openRoundsToMigrate =
                noConflict ? sourceSide.openRounds() : List.of();

        // 个人最好预演：双方已完成回合合并到保留会员后按可比组别重算（只预判标记，不改任何成绩）
        List<MemberMergeDtos.PbGroupResult> pbGroups = previewPersonalBests(keep, source);

        return new MemberMergeDtos.MergePlan(
                keep.getBalance(), source.getBalance(), balanceAfter,
                keep.getTotalSpend(), source.getTotalSpend(), spendAfter,
                keep.getLevel(), RangeDict.memberLevelName(keep.getLevel()),
                levelAfter, RangeDict.memberLevelName(levelAfter), downgraded,
                toMigrate, duplicates, openRoundsToMigrate,
                sourceSide.historyRounds().size(), rentalsToMigrate, lanesToMigrate,
                sourceSide.certApplications().size(), pbGroups);
    }

    // ================= 个人最好：预演预判 + 迁移后重归组 =================

    /**
     * 预演：把双方已提交回合按保留会员的可比组别（弓种 + 射距 + 箭数，忽略原档案归属）合并分组，
     * 用与提交 / 历史修复完全相同的口径（PersonalBestSupport）预判每组赢家，
     * 只计算结果，绝不在预演阶段改标记、改成绩。
     */
    private List<MemberMergeDtos.PbGroupResult> previewPersonalBests(Member keep, Member source) {
        List<Round> combined = new ArrayList<>();
        combined.addAll(roundRepository.findByMemberIdAndStatusOrderByStartTimeDesc(keep.getId(), "SUBMITTED"));
        combined.addAll(roundRepository.findByMemberIdAndStatusOrderByStartTimeDesc(source.getId(), "SUBMITTED"));
        Map<PersonalBestGroupKey, List<Round>> groups = groupByComparableForMerge(combined);
        List<MemberMergeDtos.PbGroupResult> results = new ArrayList<>();
        for (Map.Entry<PersonalBestGroupKey, List<Round>> entry : groups.entrySet()) {
            List<Round> group = entry.getValue();
            Round winner = PersonalBestSupport.winnerOf(group);
            if (winner == null) {
                continue;
            }
            String side = winner.getMember() != null && winner.getMember().getId().equals(source.getId())
                    ? "SOURCE" : "KEEP";
            results.add(new MemberMergeDtos.PbGroupResult(
                    entry.getKey().label(), group.size(), winner.getRoundNo(),
                    winner.getMember() == null ? "" : winner.getMember().getCardNo(),
                    winner.getTotalScore(), PersonalBestSupport.effectiveCompletedAt(winner), side));
        }
        results.sort(Comparator.comparing(MemberMergeDtos.PbGroupResult::groupLabel));
        return results;
    }

    private record PbChange(PersonalBestSupport.GroupKey key, Long winnerRoundId) {
    }

    /**
     * 迁移后重算：扫描保留会员全部已提交回合，按可比组别用统一口径重新落唯一最好标记。
     * 只改 rounds.personal_best；箭值、总分、平均环、完成时间、认证证据外键一律不动。
     */
    private List<PbChange> recomputePersonalBests(Long keepMemberId) {
        List<Round> rounds = roundRepository
                .findByMemberIdAndStatusOrderByStartTimeDesc(keepMemberId, "SUBMITTED");
        Map<PersonalBestSupport.GroupKey, List<Round>> groups = groupByComparable(rounds);
        List<Round> touched = new ArrayList<>();
        List<PbChange> changes = new ArrayList<>();
        for (Map.Entry<PersonalBestSupport.GroupKey, List<Round>> entry : groups.entrySet()) {
            List<Round> group = entry.getValue();
            Round winner = PersonalBestSupport.winnerOf(group);
            PersonalBestSupport.applyBestMarks(group);
            touched.addAll(group);
            if (winner != null) {
                changes.add(new PbChange(entry.getKey(), winner.getId()));
            }
        }
        if (!touched.isEmpty()) {
            roundRepository.saveAll(touched);
        }
        return changes;
    }

    private Map<PersonalBestSupport.GroupKey, List<Round>> groupByComparable(List<Round> rounds) {
        Map<PersonalBestSupport.GroupKey, List<Round>> raw = new LinkedHashMap<>();
        for (Round round : rounds) {
            PersonalBestSupport.Eligibility check = PersonalBestSupport.check(round);
            if (!check.eligible()) {
                // 已完成但射距 / 箭数 / 弓种无法确定的回合：保留原始成绩，不静默归组（与历史修复同口径）
                continue;
            }
            raw.computeIfAbsent(check.key(), k -> new ArrayList<>()).add(round);
        }
        // 顺序稳定：弓种 → 射距 → 箭数
        Map<PersonalBestSupport.GroupKey, List<Round>> ordered = new TreeMap<>(
                Comparator.comparing(PersonalBestSupport.GroupKey::bowType)
                        .thenComparing(PersonalBestSupport.GroupKey::distance)
                        .thenComparing(PersonalBestSupport.GroupKey::arrowCount));
        ordered.putAll(raw);
        return ordered;
    }

    /** 预演专用组别键：两张卡的回合将合到同一会员，分组时忽略原 memberId */
    private record PersonalBestGroupKey(String bowType, Integer distance, Integer arrowCount) {
        String label() {
            return RangeDict.bowTypeName(bowType) + " · " + distance + " 米 · " + arrowCount + " 支";
        }
    }

    private Map<PersonalBestGroupKey, List<Round>> groupByComparableForMerge(List<Round> rounds) {
        Map<PersonalBestGroupKey, List<Round>> raw = new LinkedHashMap<>();
        for (Round round : rounds) {
            PersonalBestSupport.Eligibility check = PersonalBestSupport.check(round);
            if (!check.eligible()) {
                continue;
            }
            PersonalBestSupport.GroupKey k = check.key();
            raw.computeIfAbsent(new PersonalBestGroupKey(k.bowType(), k.distance(), k.arrowCount()),
                    x -> new ArrayList<>()).add(round);
        }
        Map<PersonalBestGroupKey, List<Round>> ordered = new TreeMap<>(
                Comparator.comparing(PersonalBestGroupKey::bowType)
                        .thenComparing(PersonalBestGroupKey::distance)
                        .thenComparing(PersonalBestGroupKey::arrowCount));
        ordered.putAll(raw);
        return ordered;
    }

    // ================= 指纹：预演后变化检测 =================

    /**
     * 数据指纹：覆盖题目要求的全部「预演后变化即拒绝」维度 ——
     * 余额 / 累计消费 / 等级、课程报名（课程 + 报名时间）、未结束回合（回合号 + 状态）、
     * 历史成绩（回合号 + 状态 + 总分 + 完成时间，箭值被改动会体现为总分变化）、
     * 当前器材租借、认证申请（申请号 + 状态 + 有效期）、有效认证（申请号集合）、开台占用。
     * 列表全部排序后再哈希，保证仅顺序变化不被判为变化。
     */
    private String fingerprint(MemberMergeDtos.MergeSide side) {
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("memberId", side.memberId());
        fp.put("balance", side.balance());
        fp.put("totalSpend", side.totalSpend());
        fp.put("level", side.level());
        fp.put("enrollments", side.enrollments().stream()
                .map(e -> e.courseId() + "@" + e.enrollTime()).sorted().toList());
        fp.put("openRounds", side.openRounds().stream()
                .map(r -> r.roundId() + "#" + r.status()).sorted().toList());
        fp.put("historyRounds", side.historyRounds().stream()
                .map(r -> r.roundId() + "#SUBMITTED#" + r.totalScore() + "#"
                        + (r.personalBest() ? 1 : 0) + "@" + r.completedAt())
                .sorted().toList());
        fp.put("rentals", side.rentals().stream()
                .map(MemberMergeDtos.RentalItem::equipmentId).sorted().toList());
        fp.put("occupiedLanes", side.occupiedLanes().stream()
                .map(MemberMergeDtos.OccupiedLaneItem::laneId).sorted().toList());
        fp.put("certApplications", side.certApplications().stream()
                .map(c -> c.applicationId() + "#" + c.status() + "#" + c.validUntil()).sorted().toList());
        fp.put("validCerts", side.validCerts().stream()
                .map(MemberMergeDtos.CertAppItem::applicationId).sorted().toList());
        return writeJson(fp);
    }

    /** 逐维度比对，给出可读的变化说明（供经理判断为何被拒绝） */
    private List<String> diffFingerprint(String oldJson, String newJson) {
        List<String> changes = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> oldMap = objectMapper.readValue(oldJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> newMap = objectMapper.readValue(newJson, Map.class);
            if (!Objects.equals(oldMap.get("balance"), newMap.get("balance")))
                changes.add("余额变化");
            if (!Objects.equals(oldMap.get("totalSpend"), newMap.get("totalSpend")))
                changes.add("累计消费变化");
            if (!Objects.equals(oldMap.get("level"), newMap.get("level")))
                changes.add("等级变化");
            if (!Objects.equals(oldMap.get("enrollments"), newMap.get("enrollments")))
                changes.add("课程报名变化");
            if (!Objects.equals(oldMap.get("openRounds"), newMap.get("openRounds")))
                changes.add("计分回合变化");
            if (!Objects.equals(oldMap.get("historyRounds"), newMap.get("historyRounds")))
                changes.add("历史成绩变化");
            if (!Objects.equals(oldMap.get("rentals"), newMap.get("rentals")))
                changes.add("器材租借变化");
            if (!Objects.equals(oldMap.get("occupiedLanes"), newMap.get("occupiedLanes")))
                changes.add("开台占用变化");
            if (!Objects.equals(oldMap.get("certApplications"), newMap.get("certApplications")))
                changes.add("认证申请变化");
            if (!Objects.equals(oldMap.get("validCerts"), newMap.get("validCerts")))
                changes.add("有效认证变化");
        } catch (Exception e) {
            changes.add("预演指纹无法核对");
        }
        return changes;
    }

    // ================= 迁移辅助 =================

    /**
     * 锁定双方全部相关业务行（回合 / 认证申请 / 在租器材 / 占用箭道），
     * 与记箭、提交、租借归还、认证复核、开台收台并发互斥；全部按 id 升序加锁防死锁。
     */
    private void lockBusinessRows(Member keep, Member source) {
        List<Member> both = List.of(keep, source);
        roundRepository.findByMemberInForUpdate(both);
        certApplicationRepository.findByMemberInForUpdate(both);
        List<Long> equipIds = new ArrayList<>(equipmentRepository.findByRenterId(keep.getId()).stream()
                .map(Equipment::getId).toList());
        equipmentRepository.findByRenterId(source.getId()).stream().map(Equipment::getId)
                .forEach(id -> {
                    if (!equipIds.contains(id)) {
                        equipIds.add(id);
                    }
                });
        if (!equipIds.isEmpty()) {
            equipmentRepository.findAllByIdForUpdate(equipIds);
        }
        List<Long> laneIds = new ArrayList<>(laneRepository.findByOccupantId(keep.getId()).stream()
                .map(Lane::getId).toList());
        laneRepository.findByOccupantId(source.getId()).stream().map(Lane::getId)
                .forEach(id -> {
                    if (!laneIds.contains(id)) {
                        laneIds.add(id);
                    }
                });
        if (!laneIds.isEmpty()) {
            laneRepository.findAllByIdForUpdate(laneIds);
        }
        // 报名行：锁双方全部报名，与并发报名 / 取消互斥
        List<Long> enrollIds = new ArrayList<>(enrollRepository.findByMemberIdOrderByIdAsc(keep.getId()).stream()
                .map(CourseEnroll::getId).toList());
        enrollRepository.findByMemberIdOrderByIdAsc(source.getId()).stream().map(CourseEnroll::getId)
                .forEach(id -> {
                    if (!enrollIds.contains(id)) {
                        enrollIds.add(id);
                    }
                });
        if (!enrollIds.isEmpty()) {
            enrollRepository.findAllById(enrollIds);
        }
    }

    /**
     * 预演失效独立落库：确认事务随后回滚，但 STALE 状态与轨迹必须留下，
     * 经理能在合并轨迹里看到该预演为何失效。按 id 重新加载，避免把外层会话的脏状态带进新事务。
     */
    private void markStale(MemberMerge merge, String reason) {
        Long mergeId = merge.getId();
        String detail = clip(reason);
        newTransaction.executeWithoutResult(status -> {
            MemberMerge fresh = mergeRepository.findById(mergeId).orElse(null);
            if (fresh != null && MemberMerge.STATUS_PREVIEWED.equals(fresh.getStatus())) {
                fresh.setStatus(MemberMerge.STATUS_STALE);
                mergeRepository.save(fresh);
                mergeLogRepository.save(new MemberMergeLog(mergeId, MemberMergeLog.ACTION_STALE,
                        "系统", detail, LocalDateTime.now()));
            }
        });
    }

    // ================= 视图组装 =================

    private MemberMergeDtos.MergeDetailView toDetailView(MemberMerge merge) {
        Member keep = memberRepository.findById(merge.getKeepMemberId()).orElse(null);
        Member source = memberRepository.findById(merge.getSourceMemberId()).orElse(null);
        MemberMergeDtos.MergeSummary summary = new MemberMergeDtos.MergeSummary(
                merge.getId(), merge.getMergeNo(), merge.getStatus(),
                RangeDict.mergeStatusName(merge.getStatus()),
                merge.getKeepMemberId(), keep == null ? "" : keep.getCardNo(),
                keep == null ? "" : keep.getName(),
                merge.getSourceMemberId(), source == null ? "" : source.getCardNo(),
                source == null ? "" : source.getName(),
                merge.getReason(), merge.getPreviewOperator(), merge.getPreviewedAt(),
                merge.getConfirmedBy(), merge.getConfirmedAt());
        MemberMergeDtos.MergePreviewView snapshot = readPreview(merge.getSnapshot());
        List<MemberMergeDtos.MergeLogView> logs = mergeLogRepository.findByMergeIdOrderByIdAsc(merge.getId())
                .stream()
                .map(l -> new MemberMergeDtos.MergeLogView(l.getId(), l.getAction(),
                        RangeDict.mergeActionName(l.getAction()), l.getOperator(), l.getDetail(), l.getCreatedAt()))
                .toList();
        return new MemberMergeDtos.MergeDetailView(summary, snapshot, logs);
    }

    private MemberMergeDtos.MergeSummary toSummary(MemberMerge merge) {
        Member keep = memberRepository.findById(merge.getKeepMemberId()).orElse(null);
        Member source = memberRepository.findById(merge.getSourceMemberId()).orElse(null);
        return new MemberMergeDtos.MergeSummary(
                merge.getId(), merge.getMergeNo(), merge.getStatus(),
                RangeDict.mergeStatusName(merge.getStatus()),
                merge.getKeepMemberId(), keep == null ? "" : keep.getCardNo(),
                keep == null ? "" : keep.getName(),
                merge.getSourceMemberId(), source == null ? "" : source.getCardNo(),
                source == null ? "" : source.getName(),
                merge.getReason(), merge.getPreviewOperator(), merge.getPreviewedAt(),
                merge.getConfirmedBy(), merge.getConfirmedAt());
    }

    // ================= 工具 =================

    private void requireManager(String role, String operator) {
        if (!"MANAGER".equals(role == null ? "" : role.trim().toUpperCase())) {
            throw new ForbiddenException("无权操作：重复档案合并只能由值班经理发起与确认");
        }
        String name = operator == null ? "" : operator.trim();
        if (name.isEmpty()) {
            throw new BizException("请填写值班经理姓名");
        }
        if (name.length() > 32) {
            throw new BizException("值班经理姓名过长");
        }
    }

    private String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new BizException("请填写合并原因");
        }
        if (r.length() > 500) {
            throw new BizException("合并原因不能超过 500 字");
        }
        return r;
    }

    private Member requireMember(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：id=" + id));
    }

    private void requireUsable(Member member, String role2) {
        if (Member.STATUS_MERGED.equals(member.getStatus())) {
            throw new BizException(role2 + " [" + member.getCardNo() + "]（" + member.getName()
                    + "）已合并封存，不能再次参与合并");
        }
    }

    private void log(Long mergeId, String action, String operator, String detail, LocalDateTime at) {
        mergeLogRepository.save(new MemberMergeLog(mergeId, action, operator, clip(detail), at));
    }

    private String nextMergeNo() {
        String no = "MG" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (mergeRepository.existsByMergeNo(no)) {
            no = "MG" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private static String displayStatus(CertApplication app, LocalDateTime now) {
        if ("APPROVED".equals(app.getStatus())
                && app.getValidUntil() != null && app.getValidUntil().isBefore(now)) {
            return "EXPIRED";
        }
        return app.getStatus();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException("合并预演结果序列化失败：" + e.getMessage());
        }
    }

    private MemberMergeDtos.MergePreviewView readPreview(String json) {
        try {
            return objectMapper.readValue(json, MemberMergeDtos.MergePreviewView.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static String fmt(LocalDateTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
