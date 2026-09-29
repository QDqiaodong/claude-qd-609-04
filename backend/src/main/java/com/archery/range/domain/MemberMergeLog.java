package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 重复会员档案合并轨迹（成功合并一条）。
 *
 * 记录合并操作者（值班经理）、原因、两侧原卡号 / 原姓名、合并前余额与累计消费、
 * 合并后金额与等级、各类业务关系迁移 / 去重数量及逐项明细。
 * 来源档案不删除，仍以 status=MERGED 留在 members 表，凭此轨迹与来源档案随时可查。
 */
@Entity
@Table(name = "member_merge_log")
public class MemberMergeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_no", nullable = false, unique = true, length = 24)
    private String mergeNo;

    @Column(name = "preview_id", nullable = false)
    private Long previewId;

    @Column(name = "keep_member_id", nullable = false)
    private Long keepMemberId;

    @Column(name = "source_member_id", nullable = false)
    private Long sourceMemberId;

    @Column(name = "keep_card_no", nullable = false, length = 24)
    private String keepCardNo;

    @Column(name = "source_card_no", nullable = false, length = 24)
    private String sourceCardNo;

    @Column(name = "keep_name", nullable = false, length = 32)
    private String keepName;

    @Column(name = "source_name", nullable = false, length = 32)
    private String sourceName;

    @Column(name = "keep_balance_before", nullable = false, precision = 10, scale = 2)
    private BigDecimal keepBalanceBefore;

    @Column(name = "source_balance_before", nullable = false, precision = 10, scale = 2)
    private BigDecimal sourceBalanceBefore;

    @Column(name = "keep_spend_before", nullable = false, precision = 12, scale = 2)
    private BigDecimal keepSpendBefore;

    @Column(name = "source_spend_before", nullable = false, precision = 12, scale = 2)
    private BigDecimal sourceSpendBefore;

    @Column(name = "balance_after", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "total_spend_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSpendAfter;

    @Column(name = "level_before", nullable = false, length = 16)
    private String levelBefore;

    @Column(name = "level_after", nullable = false, length = 16)
    private String levelAfter;

    @Column(name = "rounds_moved", nullable = false)
    private Integer roundsMoved;

    @Column(name = "enrollments_moved", nullable = false)
    private Integer enrollmentsMoved;

    @Column(name = "enrollments_dropped", nullable = false)
    private Integer enrollmentsDropped;

    @Column(name = "equipment_moved", nullable = false)
    private Integer equipmentMoved;

    @Column(name = "lanes_moved", nullable = false)
    private Integer lanesMoved;

    @Column(name = "certs_moved", nullable = false)
    private Integer certsMoved;

    @Column(name = "tournament_moved", nullable = false)
    private Integer tournamentMoved;

    /** 逐项迁移 / 去重明细 JSON */
    @Lob
    @Column(name = "detail_json")
    private String detailJson;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 32)
    private String operator;

    @Column(name = "merged_at", nullable = false)
    private LocalDateTime mergedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMergeNo() {
        return mergeNo;
    }

    public void setMergeNo(String mergeNo) {
        this.mergeNo = mergeNo;
    }

    public Long getPreviewId() {
        return previewId;
    }

    public void setPreviewId(Long previewId) {
        this.previewId = previewId;
    }

    public Long getKeepMemberId() {
        return keepMemberId;
    }

    public void setKeepMemberId(Long keepMemberId) {
        this.keepMemberId = keepMemberId;
    }

    public Long getSourceMemberId() {
        return sourceMemberId;
    }

    public void setSourceMemberId(Long sourceMemberId) {
        this.sourceMemberId = sourceMemberId;
    }

    public String getKeepCardNo() {
        return keepCardNo;
    }

    public void setKeepCardNo(String keepCardNo) {
        this.keepCardNo = keepCardNo;
    }

    public String getSourceCardNo() {
        return sourceCardNo;
    }

    public void setSourceCardNo(String sourceCardNo) {
        this.sourceCardNo = sourceCardNo;
    }

    public String getKeepName() {
        return keepName;
    }

    public void setKeepName(String keepName) {
        this.keepName = keepName;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public BigDecimal getKeepBalanceBefore() {
        return keepBalanceBefore;
    }

    public void setKeepBalanceBefore(BigDecimal keepBalanceBefore) {
        this.keepBalanceBefore = keepBalanceBefore;
    }

    public BigDecimal getSourceBalanceBefore() {
        return sourceBalanceBefore;
    }

    public void setSourceBalanceBefore(BigDecimal sourceBalanceBefore) {
        this.sourceBalanceBefore = sourceBalanceBefore;
    }

    public BigDecimal getKeepSpendBefore() {
        return keepSpendBefore;
    }

    public void setKeepSpendBefore(BigDecimal keepSpendBefore) {
        this.keepSpendBefore = keepSpendBefore;
    }

    public BigDecimal getSourceSpendBefore() {
        return sourceSpendBefore;
    }

    public void setSourceSpendBefore(BigDecimal sourceSpendBefore) {
        this.sourceSpendBefore = sourceSpendBefore;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public BigDecimal getTotalSpendAfter() {
        return totalSpendAfter;
    }

    public void setTotalSpendAfter(BigDecimal totalSpendAfter) {
        this.totalSpendAfter = totalSpendAfter;
    }

    public String getLevelBefore() {
        return levelBefore;
    }

    public void setLevelBefore(String levelBefore) {
        this.levelBefore = levelBefore;
    }

    public String getLevelAfter() {
        return levelAfter;
    }

    public void setLevelAfter(String levelAfter) {
        this.levelAfter = levelAfter;
    }

    public Integer getRoundsMoved() {
        return roundsMoved;
    }

    public void setRoundsMoved(Integer roundsMoved) {
        this.roundsMoved = roundsMoved;
    }

    public Integer getEnrollmentsMoved() {
        return enrollmentsMoved;
    }

    public void setEnrollmentsMoved(Integer enrollmentsMoved) {
        this.enrollmentsMoved = enrollmentsMoved;
    }

    public Integer getEnrollmentsDropped() {
        return enrollmentsDropped;
    }

    public void setEnrollmentsDropped(Integer enrollmentsDropped) {
        this.enrollmentsDropped = enrollmentsDropped;
    }

    public Integer getEquipmentMoved() {
        return equipmentMoved;
    }

    public void setEquipmentMoved(Integer equipmentMoved) {
        this.equipmentMoved = equipmentMoved;
    }

    public Integer getLanesMoved() {
        return lanesMoved;
    }

    public void setLanesMoved(Integer lanesMoved) {
        this.lanesMoved = lanesMoved;
    }

    public Integer getCertsMoved() {
        return certsMoved;
    }

    public void setCertsMoved(Integer certsMoved) {
        this.certsMoved = certsMoved;
    }

    public Integer getTournamentMoved() {
        return tournamentMoved;
    }

    public void setTournamentMoved(Integer tournamentMoved) {
        this.tournamentMoved = tournamentMoved;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public LocalDateTime getMergedAt() {
        return mergedAt;
    }

    public void setMergedAt(LocalDateTime mergedAt) {
        this.mergedAt = mergedAt;
    }
}
