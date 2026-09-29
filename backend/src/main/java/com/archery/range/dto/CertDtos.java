package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 弓种能力认证模块的入参与出参。
 */
public final class CertDtos {

    private CertDtos() {
    }

    // ---------------- 规则 ----------------

    /** 新建规则谱系 v1 / 发布新版本 */
    public record RuleSaveReq(
            @NotBlank(message = "请选择弓种") String bowType,
            @NotNull(message = "请选择射距") Integer distance,
            @NotNull(message = "请选择每组箭数") Integer groupSize,
            @NotNull(message = "请填写最少回合数") Integer minRounds,
            @NotNull(message = "请填写平均环通过标准") BigDecimal minAverage,
            @NotNull(message = "请填写有效期月数") Integer validityMonths,
            @Size(max = 200, message = "规则说明不能超过 200 字") String note,
            @NotBlank(message = "请填写发布教练姓名") String operator) {
    }

    /** 停用规则只需操作人 */
    public record RuleOperatorReq(@NotBlank(message = "请填写教练姓名") String operator) {
    }

    public record RuleView(
            Long id,
            String ruleCode,
            Integer version,
            String bowType,
            String bowTypeName,
            Integer distance,
            Integer groupSize,
            Integer minRounds,
            BigDecimal minAverage,
            Integer validityMonths,
            String status,
            String statusName,
            String note,
            String createdBy,
            LocalDateTime createdAt) {
    }

    // ---------------- 申请 / 复核 ----------------

    /** 建立认证申请：会员 + 规则版本 + 明确证据窗口 + 证据回合 */
    public record CreateAppReq(
            @NotNull(message = "请选择会员") Long memberId,
            @NotNull(message = "请选择认证规则版本") Long ruleId,
            @NotNull(message = "请选择证据窗口开始时间") LocalDateTime windowStart,
            @NotNull(message = "请选择证据窗口结束时间") LocalDateTime windowEnd,
            List<Long> roundIds,
            @NotBlank(message = "请填写教练姓名") String operator) {
    }

    /** 复核决定：APPROVE 通过 / REJECT 驳回 / NEED_MORE 要求补充证据 */
    public record DecideReq(
            @NotBlank(message = "请选择复核结论") String action,
            @NotBlank(message = "请填写复核教练姓名") String operator,
            @Size(max = 500, message = "复核意见不能超过 500 字") String note) {
    }

    /** 待补充证据后重新提交：新的窗口与回合，规则沿用申请原定版本 */
    public record ResubmitReq(
            @NotNull(message = "请选择证据窗口开始时间") LocalDateTime windowStart,
            @NotNull(message = "请选择证据窗口结束时间") LocalDateTime windowEnd,
            List<Long> roundIds,
            @NotBlank(message = "请填写教练姓名") String operator) {
    }

    /** 撤回已通过认证：必须写明原因 */
    public record WithdrawReq(
            @NotBlank(message = "请填写教练姓名") String operator,
            @NotBlank(message = "请填写撤回原因") @Size(max = 500, message = "撤回原因不能超过 500 字") String reason) {
    }

    /** 证据回合（详情中展示用了哪些回合） */
    public record EvidenceRound(
            Long roundId,
            String roundNo,
            LocalDateTime startTime,
            String laneNo,
            Integer distance,
            String bowType,
            String bowTypeName,
            Integer arrowCount,
            Integer totalScore,
            BigDecimal averageScore,
            String status,
            String statusName) {
    }

    /** 评定记录：规则快照 + 证据窗口 + 系统预评结果 */
    public record EvalView(
            Long id,
            Integer evalSeq,
            Long ruleId,
            String ruleCode,
            Integer ruleVersion,
            Integer snapGroupSize,
            Integer snapMinRounds,
            BigDecimal snapMinAverage,
            Integer snapValidityMonths,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            Integer roundCount,
            Integer arrowTotal,
            Integer totalScore,
            BigDecimal avgScore,
            Boolean passFlag,
            String evalMessage,
            String createdBy,
            LocalDateTime createdAt,
            List<EvidenceRound> rounds) {
    }

    /** 状态轨迹 */
    public record LogItem(
            String action,
            String actionName,
            String operator,
            String role,
            String roleName,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 申请列表行 */
    public record AppSummary(
            Long id,
            String appNo,
            Long memberId,
            String memberName,
            String bowType,
            String bowTypeName,
            Integer distance,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String status,
            String statusName,
            /** 展示状态：APPROVED 过期时派生为 EXPIRED，库中状态不变 */
            String displayStatus,
            String displayStatusName,
            Integer evalSeq,
            Boolean passFlag,
            BigDecimal avgScore,
            String createdBy,
            LocalDateTime createdAt,
            String reviewedBy,
            LocalDateTime reviewedAt,
            String reviewNote,
            LocalDateTime validFrom,
            LocalDateTime validUntil,
            String withdrawnBy,
            LocalDateTime withdrawnAt,
            String withdrawReason) {
    }

    /** 申请详情：申请信息 + 当前评定（含证据回合）+ 历次评定 + 状态轨迹 */
    public record AppDetail(
            AppSummary summary,
            EvalView currentEval,
            List<EvalView> evalHistory,
            List<LogItem> logs) {
    }

    /** 会员当前持有的有效认证（已通过、未过期、未撤回、未被取代） */
    public record CertItem(
            Long applicationId,
            String appNo,
            String bowType,
            String bowTypeName,
            Integer distance,
            String scopeText,
            String ruleCode,
            Integer ruleVersion,
            LocalDateTime validFrom,
            LocalDateTime validUntil) {
    }

    /** 会员认证页：当前有效认证 + 全部历史申请 */
    public record MemberCerts(
            Long memberId,
            String memberName,
            List<CertItem> current,
            List<AppSummary> history) {
    }
}
