package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 重复会员档案合并的入参与出参。
 *
 * 预演 ({@link MergePreviewReq}/{@link MergePreviewView}) 逐项列出两张卡的
 * 余额、累计消费、等级、课程报名、未结束回合、历史成绩、当前器材租借、认证申请与有效认证，
 * 并明确给出互斥项清单与合并结果；确认 ({@link MergeConfirmReq}) 必须携带预演单 id，
 * 后端以预演固化的指纹 / 快照为唯一依据。
 */
public final class MemberMergeDtos {

    private MemberMergeDtos() {
    }

    // ---------------- 入参 ----------------

    public record MergePreviewReq(
            @NotNull(message = "请选择保留档案") Long keepMemberId,
            @NotNull(message = "请选择来源档案") Long sourceMemberId,
            @NotBlank(message = "请填写合并原因") String reason,
            @NotBlank(message = "请填写值班经理姓名") String operator,
            @NotBlank(message = "缺少操作角色") String role) {
    }

    public record MergeConfirmReq(
            @NotNull(message = "缺少合并预演单") Long mergeId,
            @NotBlank(message = "请填写值班经理姓名") String operator,
            @NotBlank(message = "缺少操作角色") String role) {
    }

    // ---------------- 单侧档案视图 ----------------

    public record EnrollItem(
            Long enrollId,
            Long courseId,
            String courseName,
            LocalDateTime classTime,
            LocalDateTime enrollTime) {
    }

    public record OpenRoundItem(
            Long roundId,
            String roundNo,
            String laneNo,
            Integer distance,
            String bowType,
            String bowTypeName,
            LocalDateTime startTime,
            String status,
            String statusName) {
    }

    public record HistoryRoundItem(
            Long roundId,
            String roundNo,
            String laneNo,
            Integer distance,
            String bowType,
            String bowTypeName,
            LocalDateTime startTime,
            LocalDateTime completedAt,
            Integer arrowCount,
            Integer totalScore,
            BigDecimal averageScore,
            Boolean personalBest) {
    }

    public record RentalItem(
            Long equipmentId,
            String equipCode,
            String type,
            String typeName,
            String brand,
            LocalDateTime rentedAt) {
    }

    public record OccupiedLaneItem(
            Long laneId,
            String laneNo,
            Integer distance,
            LocalDateTime openedAt,
            Integer plannedHours) {
    }

    public record CertAppItem(
            Long applicationId,
            String appNo,
            String bowType,
            String bowTypeName,
            Integer distance,
            String status,
            String statusName,
            String displayStatus,
            String displayStatusName,
            LocalDateTime validFrom,
            LocalDateTime validUntil) {
    }

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
            String status,
            List<EnrollItem> enrollments,
            List<OpenRoundItem> openRounds,
            List<HistoryRoundItem> historyRounds,
            List<RentalItem> rentals,
            List<OccupiedLaneItem> occupiedLanes,
            List<CertAppItem> certApplications,
            List<CertAppItem> validCerts) {
    }

    // ---------------- 互斥项 ----------------

    /**
     * 互斥项。type：
     * ONGOING_ROUND 两张卡都有未结束计分回合；
     * SAME_EQUIPMENT 同一件器材同时挂在两张卡名下；
     * VALID_CERT_SCOPE 同一弓种且适用范围重叠的有效认证。
     */
    public record ConflictItem(
            String type,
            String typeName,
            String target,
            String detail) {
    }

    // ---------------- 合并结果（预演计算，确认照此执行） ----------------

    public record DroppedEnroll(
            Long courseId,
            String courseName,
            String keptCardNo,
            LocalDateTime keptEnrollTime,
            String droppedCardNo,
            LocalDateTime droppedEnrollTime) {
    }

    public record PbGroupResult(
            String groupLabel,
            int roundCount,
            String winnerRoundNo,
            String winnerCardNo,
            Integer winnerTotalScore,
            LocalDateTime winnerCompletedAt,
            /** 赢家来源：KEEP 保留卡 / SOURCE 来源卡 */
            String winnerSide) {
    }

    public record MergePlan(
            BigDecimal keepBalanceBefore,
            BigDecimal sourceBalanceBefore,
            BigDecimal balanceAfter,
            BigDecimal keepSpendBefore,
            BigDecimal sourceSpendBefore,
            BigDecimal spendAfter,
            String keepLevelBefore,
            String keepLevelBeforeName,
            String levelAfter,
            String levelAfterName,
            boolean levelDowngraded,
            List<EnrollItem> enrollmentsToMigrate,
            List<DroppedEnroll> duplicateEnrollments,
            List<OpenRoundItem> openRoundsToMigrate,
            int historyRoundsToMigrate,
            List<RentalItem> rentalsToMigrate,
            List<OccupiedLaneItem> occupiedLanesToMigrate,
            int certApplicationsToMigrate,
            List<PbGroupResult> personalBestGroups) {
    }

    // ---------------- 预演单 ----------------

    public record MergePreviewView(
            Long mergeId,
            String mergeNo,
            String status,
            String reason,
            String previewOperator,
            LocalDateTime previewedAt,
            MergeSide keep,
            MergeSide source,
            List<ConflictItem> conflicts,
            boolean canMerge,
            MergePlan plan) {
    }

    // ---------------- 轨迹 ----------------

    public record MergeLogView(
            Long id,
            String action,
            String actionName,
            String operator,
            String detail,
            LocalDateTime createdAt) {
    }

    public record MergeSummary(
            Long mergeId,
            String mergeNo,
            String status,
            String statusName,
            Long keepMemberId,
            String keepCardNo,
            String keepName,
            Long sourceMemberId,
            String sourceCardNo,
            String sourceName,
            String reason,
            String previewOperator,
            LocalDateTime previewedAt,
            String confirmedBy,
            LocalDateTime confirmedAt) {
    }

    public record MergeDetailView(
            MergeSummary summary,
            MergePreviewView snapshot,
            List<MergeLogView> logs) {
    }

    /** 旧卡号进入：正常卡直接使用；已封存卡带跳转目标与合并时间 / 原因 */
    public record CardEntryView(
            Long memberId,
            String cardNo,
            String name,
            String status,
            boolean merged,
            Long redirectMemberId,
            String redirectCardNo,
            String redirectName,
            LocalDateTime mergedAt,
            String mergedBy,
            String mergeReason) {
    }
}
