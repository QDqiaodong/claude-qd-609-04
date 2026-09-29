package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 器材租赁模块的入参与出参。
 */
public final class EquipmentDtos {

    private EquipmentDtos() {
    }

    public record EquipmentSaveReq(
            @NotBlank(message = "器材编号不能为空") String equipCode,
            @NotBlank(message = "请选择器材类型") String type,
            @NotBlank(message = "品牌不能为空") String brand,
            @NotNull(message = "请填写租金") @DecimalMin(value = "0", message = "租金不能为负") BigDecimal rentPrice) {
    }

    public record RentReq(@NotNull(message = "请选择租借会员") Long memberId) {
    }

    public record EquipmentView(
            Long id,
            String equipCode,
            String type,
            String typeName,
            String brand,
            BigDecimal rentPrice,
            String status,
            String statusName,
            Long renterId,
            String renterName,
            LocalDateTime rentedAt) {
    }
}
