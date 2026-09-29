package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 模块九：重复会员档案合并的入参与出参。
 *
 * 流程：值班经理选定保留档案 + 来源档案发起预演（{@link MergePreviewReq}）→
 * 查看 {@link MergePreview}（两侧清单 / 互斥项 / 去重项 / 合并后金额等级）→
 * 凭预演 id 确认（{@link MergeConfirmReq}）→ 返回 {@link MergeResult} 并生成合并轨迹。
 */
public final class MergeDtos {

    private MergeDtos() {
    }

    // ---------------- 入参 ----------------

    public record MergePreviewReq(
            @NotNull(message = "请选择保留档案") Long keepMemberId,
            @NotNull(message = "请选择来源档案") Long sourceMemberId,
            @NotBlank(message = "请填写值班经理姓名") String operator,
            @NotBlank(message = "请填写合并原因") @Size(max = 500, message = "合并原因不超过 500 字") String reason) {
    }

    public record MergeConfirmReq(
            @NotNull(message = "缺少预演版本") Long previewId,
            @NotBlank(message = "请填写确认操作的值班经理姓名") String operator) {
    }

    // ---------------- 预演两侧明细 ----------------

    /** 一张卡在预演时刻的完整画像 */
    public record MergeSide(
            Long memberId,
            String cardNo,
            String name,
            String phone,
            String level,
            String levelName,
            BigDecimal balance,
            BigDecimal totalSpend,
            LocalDate registerDate,
            List<RoundItem> ongoingRounds,
            RoundHistorySummary roundHistory,
            List<EnrollItem> enrollments,
            List<EquipmentItem> rentedEquipment,
            List<LaneItem> occupiedLanes,
            List<CertItem> certApplications,
            List<CertItem> validCerts,
            List<TournamentItem> tournamentEntries) {
    }

    /** 未结束计分回合（进行中 / 暂停）——两侧同时存在即互斥 */
    public record RoundItem(
            Long id,
            String roundNo,
            String status,
            String statusName,
            String bowType,
            String bowTypeName,
            Integer distance,
            String laneNo,
            Integer arrowCount,
            LocalDateTime startTime) {
    }

    /** 历史成绩（已提交回合）统计 + 明细只给概要，原始箭值 / 完成时间绝不在合并中改写 */
    public record RoundHistorySummary(int submittedCount, int personalBestCount, List<RoundItem> rounds) {
    }

    /** 课程报名 */
    public record EnrollItem(
            Long enrollId,
            Long courseId,
            String courseName,
            LocalDateTime classTime,
            LocalDateTime enrollTime) {
    }

    /** 当前租借器材（RENTED）——两侧同时租着同一件器材即互斥 */
    public record EquipmentItem(
            Long equipmentId,
            String equipCode,
            String typeName,
            String brand,
            LocalDateTime rentedAt) {
    }

    /** 当前占用箭道（OCCUPIED，随余额关系一起迁移到保留会员名下） */
    public record LaneItem(
            Long laneId,
            String laneNo,
            Integer distance,
            LocalDateTime openedAt,
            Integer plannedHours) {
    }

    /** 认证申请 / 有效认证（同一弓种两侧都有有效认证即互斥，不自行挑一条覆盖） */
    public record CertItem(
            Long applicationId,
            String appNo,
            String bowType,
            String bowTypeName,
            Integer distance,
            String status,
            String statusName,
            String displayStatus,
            String displayStatusName,
            LocalDateTime validUntil) {
    }

    /** 赛事参与（队-会员 / 局内箭值 / 加赛箭值，两侧同赛事各在一队即互斥） */
    public record TournamentItem(
            Long tournamentId,
            String tournamentName,
            String tournamentStatus,
            Long teamId,
            String teamName,
            Integer position,
            int arrowCount) {
    }

    // ---------------- 互斥项与处理项 ----------------

    /**
     * 互斥项：必须先在业务上解除（先提交回合 / 先归还器材 / 先处理认证或赛事），
     * 系统绝不自行挑一条覆盖。
     * type: ONGOING_ROUND / RENTED_SAME_EQUIPMENT / VALID_CERT_SCOPE / SAME_TOURNAMENT
     */
    public record ConflictItem(
            String type,
            String typeName,
            String detail,
            Long keepRefId,
            Long sourceRefId,
            String keepRefLabel,
            String sourceRefLabel) {
    }

    /**
     * 可自动处理项：同一课程重复报名去重（保留报名时间较早的一条）。
     * action: DEDUP_ENROLL_KEEP_EARLIEST
     */
    public record ResolveItem(
            String type,
            String typeName,
            String detail,
            Long keptEnrollId,
            String keptSide,
            Long droppedEnrollId,
            String droppedSide,
            LocalDateTime keptEnrollTime,
            LocalDateTime droppedEnrollTime) {
    }

    /** 金额与等级的合并结果预演 */
    public record FinancePreview(
            BigDecimal keepBalance,
            BigDecimal sourceBalance,
            BigDecimal mergedBalance,
            BigDecimal keepTotalSpend,
            BigDecimal sourceTotalSpend,
            BigDecimal mergedTotalSpend,
            String keepLevel,
            String sourceLevel,
            String recalculatedLevel,
            String finalLevel,
            String finalLevelName,
            boolean levelKeptFromHigher) {
    }

    /** 迁移数量预演 */
    public record MigrationCounts(
            int roundsMoved,
            int enrollmentsMoved,
            int enrollmentsDropped,
            int equipmentMoved,
            int lanesMoved,
            int certsMoved,
            int tournamentMoved) {
    }

    // ---------------- 预演 / 结果 / 轨迹 ----------------

    public record MergePreview(
            Long previewId,
            String previewNo,
            String status,
            String operator,
            String reason,
            LocalDateTime createdAt,
            MergeSide keep,
            MergeSide source,
            List<ConflictItem> conflicts,
            boolean mergeable,
            List<ResolveItem> resolves,
            FinancePreview finance,
            MigrationCounts migration) {
    }

    public record MergeResult(
            Long mergeLogId,
            String mergeNo,
            LocalDateTime mergedAt,
            String operator,
            Long keepMemberId,
            String keepCardNo,
            Long sourceMemberId,
            String sourceCardNo,
            BigDecimal balanceAfter,
            BigDecimal totalSpendAfter,
            String levelAfter,
            String levelAfterName,
            MigrationCounts migration,
            MergePreview preview) {
    }

    /** 合并轨迹列表项（会员列表 / 详情 / 会员本人查看） */
    public record MergeTrailView(
            Long id,
            String mergeNo,
            Long keepMemberId,
            String keepCardNo,
            String keepName,
            Long sourceMemberId,
            String sourceCardNo,
            String sourceName,
            BigDecimal keepBalanceBefore,
            BigDecimal sourceBalanceBefore,
            BigDecimal balanceAfter,
            BigDecimal totalSpendAfter,
            String levelBefore,
            String levelBeforeName,
            String levelAfter,
            String levelAfterName,
            Integer roundsMoved,
            Integer enrollmentsMoved,
            Integer enrollmentsDropped,
            Integer equipmentMoved,
            Integer lanesMoved,
            Integer certsMoved,
            Integer tournamentMoved,
            String reason,
            String operator,
            LocalDateTime mergedAt,
            /** 明细解析后的列表（迁移 / 去重逐项），失败容错为空 */
            List<TrailDetailItem> details) {
    }

    public record TrailDetailItem(
            String category,
            String categoryName,
            String action,
            String detail) {
    }

    /** 旧卡号入口：来源档案封存后的跳转信息 */
    public record ArchivedRedirect(
            Long sourceMemberId,
            String sourceCardNo,
            String sourceName,
            String status,
            Long targetMemberId,
            String targetCardNo,
            String targetName,
            LocalDateTime mergedAt,
            String mergeReason,
            String mergedBy) {
    }
}
