package com.archery.range.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 计分回合模块的入参与出参。
 *
 * ArrowView 的 shotIndex 就是有序集合里的位置（从 1 开始），
 * 前端按它顺序渲染箭支徽章。
 */
public final class RoundDtos {

    private RoundDtos() {
    }

    public record RoundStartReq(
            @NotNull(message = "请选择会员") Long memberId,
            @NotNull(message = "请选择箭道") Long laneId,
            String bowType,
            @NotNull(message = "请选择箭支数") Integer arrowCount) {
    }

    /** 记一支箭：环数标记 X / 10 / 9 ... / M */
    public record ShotReq(@NotBlank(message = "请选择环数") String ring) {
    }

    public record ArrowView(Integer shotIndex, String ring, Integer ringValue) {
    }

    /**
     * 可比组别：会员 + 弓种 + 射距 + 规定箭数。
     * 个人最好成绩只在同一组内比较，不同组别各自拥有一条最好。
     */
    public record ComparableGroupView(
            Long memberId,
            String bowType,
            String bowTypeName,
            Integer distance,
            Integer arrowCount,
            String label) {
    }

    public record RoundView(
            Long id,
            String roundNo,
            Long memberId,
            String memberName,
            String memberCardNo,
            Long laneId,
            String laneNo,
            Integer distance,
            String bowType,
            String bowTypeName,
            LocalDateTime startTime,
            LocalDateTime completedAt,
            Integer arrowCount,
            Integer shotCount,
            Integer totalScore,
            BigDecimal averageScore,
            Boolean personalBest,
            String status,
            String statusName,
            /** 是否满足个人最好比较资格：已完成、箭数完整、弓种 / 射距 / 箭数 / 关联对象均可确定 */
            Boolean comparable,
            /** 所属可比组别（不可参与时为 null，原因见 excludedReason） */
            ComparableGroupView group,
            /** 不可参与比较的原因码 */
            String excludedReasonCode,
            /** 不可参与比较的明确说明（保留原始成绩，不静默归入别的组） */
            String excludedReason) {
    }

    /** 成绩单：回合 + 按 shot_index 顺序排好的每一支箭 */
    public record RoundDetailView(RoundView round, List<ArrowView> arrows) {
    }

    /** 某一会员在一个可比组别的最好成绩（该组恰好一条） */
    public record BestGroupView(
            ComparableGroupView group,
            Integer roundCount,
            RoundView bestRound) {
    }

    /** 会员详情：按可比组别列出每条当前个人最好 */
    public record MemberBestsView(
            Long memberId,
            String memberName,
            List<BestGroupView> groups) {
    }

    /** 无法参与比较的历史回合：保留原始成绩，但必须明确列出原因 */
    public record ExcludedRoundView(
            Long id,
            String roundNo,
            Long memberId,
            String memberName,
            String status,
            String statusName,
            String reasonCode,
            String reason) {
    }

    /**
     * 历史修复报告：只反映 personal_best 标记的重算结果。
     * applied=false 为预演（不落库）；多次执行 marksChanged 最终恒为 0。
     */
    public record RepairReport(
            LocalDateTime repairedAt,
            Boolean applied,
            Integer totalRounds,
            Integer eligibleRounds,
            Integer excludedRounds,
            Integer comparableGroups,
            Integer marksChanged,
            List<BestGroupView> bests,
            List<ExcludedRoundView> excluded) {
    }
}
