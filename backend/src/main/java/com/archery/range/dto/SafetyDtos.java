package com.archery.range.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 安全停射联锁台的入参与出参。
 */
public final class SafetyDtos {

    private SafetyDtos() {
    }

    /** 建立停射事件：原因类别 + 严重级别 + 现场说明 + 受影响箭道 / 器材 */
    public record CreateReq(
            @NotBlank(message = "请选择停射原因") String reason,
            @NotBlank(message = "请选择严重级别") String severity,
            @NotBlank(message = "请填写现场说明") @Size(max = 500, message = "现场说明不能超过 500 字") String description,
            List<Long> laneIds,
            List<Long> equipmentIds,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    /** 仅需操作人的动作（发起复核 / 复射放行） */
    public record OperatorReq(
            @NotBlank(message = "请填写操作人姓名") String operator) {
    }

    /** 分项复核：复核项 + 结论 + 意见（不通过时必须写原因） */
    public record ReviewReq(
            @NotBlank(message = "复核项不能为空") String item,
            @NotBlank(message = "请填写复核人姓名") String operator,
            @NotBlank(message = "请选择复核结论") String conclusion,
            @Size(max = 500, message = "复核意见不能超过 500 字") String note) {
    }

    /** 受影响箭道：锁定前状态 → 当前状态 */
    public record LaneItem(
            Long laneId,
            String laneNo,
            String prevStatus,
            String prevStatusName,
            String currentStatus,
            String currentStatusName,
            Boolean restored) {
    }

    /** 受影响器材 */
    public record EquipItem(
            Long equipmentId,
            String equipCode,
            String typeName,
            String prevStatus,
            String prevStatusName,
            String currentStatus,
            String currentStatusName,
            Boolean restored) {
    }

    /** 被暂停的回合 */
    public record RoundItem(
            Long roundId,
            String roundNo,
            String memberName,
            String laneNo,
            String prevStatus,
            String prevStatusName,
            String currentStatus,
            String currentStatusName,
            Integer shotCount,
            Integer arrowCount,
            Boolean restored) {
    }

    /** 分项复核结论（含历史轮次） */
    public record ReviewItem(
            String item,
            String itemName,
            Integer reviewRound,
            String conclusion,
            String conclusionName,
            String reviewer,
            LocalDateTime reviewedAt,
            String note) {
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

    /** 列表行 */
    public record EventSummary(
            Long id,
            String eventNo,
            String reason,
            String reasonName,
            String severity,
            String severityName,
            String status,
            String statusName,
            String createdBy,
            LocalDateTime createdAt,
            Integer laneCount,
            Integer equipmentCount,
            Integer roundCount,
            String releasedBy,
            LocalDateTime releasedAt) {
    }

    /** 事件详情：基本信息 + 受影响资源 + 复核记录 + 完整状态轨迹 */
    public record EventView(
            Long id,
            String eventNo,
            String reason,
            String reasonName,
            String severity,
            String severityName,
            String description,
            String status,
            String statusName,
            Integer reviewRound,
            String createdBy,
            LocalDateTime createdAt,
            String releasedBy,
            LocalDateTime releasedAt,
            List<LaneItem> lanes,
            List<EquipItem> equipment,
            List<RoundItem> rounds,
            List<ReviewItem> reviews,
            List<LogItem> logs) {
    }
}
