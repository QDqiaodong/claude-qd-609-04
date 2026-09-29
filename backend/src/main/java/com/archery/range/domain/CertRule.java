package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 弓种认证规则（带版本）。
 *
 * 同一弓种 + 射距构成一条规则谱系（ruleCode，如 RECURVE-50），version 从 1 递增。
 * 规则调整 = 发布新版本：旧版本转 RETIRED，不能再用于新申请；
 * 已发出的认证在评定记录（cert_eval）里持有当时的规则快照，历史不被改写。
 */
@Entity
@Table(name = "cert_rule")
public class CertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则谱系：弓种-射距，如 RECURVE-50 */
    @Column(name = "rule_code", nullable = false, length = 24)
    private String ruleCode;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "bow_type", nullable = false, length = 16)
    private String bowType;

    /** 射距（米）：10/18/30/50 */
    @Column(nullable = false)
    private Integer distance;

    /** 每组箭数要求：6 或 12 */
    @Column(name = "group_size", nullable = false)
    private Integer groupSize;

    /** 证据窗口内最少完成回合数 */
    @Column(name = "min_rounds", nullable = false)
    private Integer minRounds;

    /** 通过标准：证据窗口平均每支箭环值下限 */
    @Column(name = "min_average", nullable = false, precision = 4, scale = 2)
    private BigDecimal minAverage;

    /** 认证通过后的有效期（月） */
    @Column(name = "validity_months", nullable = false)
    private Integer validityMonths;

    /** ACTIVE 现行 / RETIRED 已停用 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 200)
    private String note;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getBowType() {
        return bowType;
    }

    public void setBowType(String bowType) {
        this.bowType = bowType;
    }

    public Integer getDistance() {
        return distance;
    }

    public void setDistance(Integer distance) {
        this.distance = distance;
    }

    public Integer getGroupSize() {
        return groupSize;
    }

    public void setGroupSize(Integer groupSize) {
        this.groupSize = groupSize;
    }

    public Integer getMinRounds() {
        return minRounds;
    }

    public void setMinRounds(Integer minRounds) {
        this.minRounds = minRounds;
    }

    public BigDecimal getMinAverage() {
        return minAverage;
    }

    public void setMinAverage(BigDecimal minAverage) {
        this.minAverage = minAverage;
    }

    public Integer getValidityMonths() {
        return validityMonths;
    }

    public void setValidityMonths(Integer validityMonths) {
        this.validityMonths = validityMonths;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
