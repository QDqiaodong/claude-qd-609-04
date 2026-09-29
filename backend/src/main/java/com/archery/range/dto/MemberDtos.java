package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 会员模块的入参与出参。
 */
public final class MemberDtos {

    private MemberDtos() {
    }

    public record MemberSaveReq(
            @NotBlank(message = "会员卡号不能为空") String cardNo,
            @NotBlank(message = "姓名不能为空") String name,
            @NotBlank(message = "手机号不能为空") @Pattern(regexp = "1[3-9]\\d{9}", message = "手机号格式不正确") String phone,
            @NotBlank(message = "请选择会员等级") String level,
            @NotNull(message = "请填写初始余额") @DecimalMin(value = "0", message = "余额不能为负") BigDecimal balance) {
    }

    public record RechargeReq(
            @NotNull(message = "请填写充值金额") @DecimalMin(value = "0.01", message = "充值金额必须大于 0") BigDecimal amount) {
    }

    public record MemberView(
            Long id,
            String cardNo,
            String name,
            String phone,
            String level,
            String levelName,
            BigDecimal balance,
            LocalDate registerDate,
            BigDecimal totalSpend,
            BigDecimal discount,
            Long roundCount,
            /** 当前持有的个人最好条数（按可比组别：弓种 + 射距 + 箭数，每组至多一条） */
            Integer bestGroupCount,
            Integer validCertCount,
            /** ACTIVE 在用 / MERGED 已合并封存（历史档案，不可继续使用） */
            String status,
            String statusName,
            /** 封存时合并去向的保留会员 id / 卡号（旧卡入口跳转） */
            Long mergedIntoId,
            String mergedIntoCardNo,
            LocalDateTime mergedAt,
            String mergeReason,
            String mergedBy) {
    }
}
