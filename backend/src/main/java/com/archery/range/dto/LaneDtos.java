package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 箭道模块的入参与出参。
 */
public final class LaneDtos {

    private LaneDtos() {
    }

    public record LaneSaveReq(
            @NotBlank(message = "道号不能为空") String laneNo,
            @NotNull(message = "距离不能为空") Integer distance,
            @NotBlank(message = "箭靶类型不能为空") String targetType,
            @NotNull(message = "每小时单价不能为空") @DecimalMin(value = "0", message = "单价不能为负") BigDecimal hourlyPrice) {
    }

    /** 开台：占用会员 + 预计时长（小时） */
    public record LaneOpenReq(
            @NotNull(message = "请选择会员") Long memberId,
            @NotNull(message = "请填写预计时长") Integer hours) {
    }

    public record LaneView(
            Long id,
            String laneNo,
            Integer distance,
            String targetType,
            String status,
            String statusName,
            BigDecimal hourlyPrice,
            Long occupantId,
            String occupantName,
            LocalDateTime openedAt,
            Integer plannedHours) {
    }
}
