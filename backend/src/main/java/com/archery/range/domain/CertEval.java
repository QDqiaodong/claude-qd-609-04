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
 * 认证评定记录：每次建立申请或补充证据后，系统按当时选定的规则快照评定一次。
 *
 * 快照字段（snap_*）固化评定时规则的箭数、最少回合数、平均环下限与有效期月数，
 * 规则后续发布新版本不影响本记录；cert_eval_round 记录本次采信的回合。
 */
@Entity
@Table(name = "cert_eval")
public class CertEval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** 评定序号：首次 1，补充证据重新评定 +1 */
    @Column(name = "eval_seq", nullable = false)
    private Integer evalSeq;

    /** 评定使用的规则版本 id */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_code", nullable = false, length = 24)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "snap_group_size", nullable = false)
    private Integer snapGroupSize;

    @Column(name = "snap_min_rounds", nullable = false)
    private Integer snapMinRounds;

    @Column(name = "snap_min_average", nullable = false, precision = 4, scale = 2)
    private BigDecimal snapMinAverage;

    @Column(name = "snap_validity_months", nullable = false)
    private Integer snapValidityMonths;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** 本次评定采信回合数 */
    @Column(name = "round_count", nullable = false)
    private Integer roundCount;

    /** 证据箭支总数 */
    @Column(name = "arrow_total", nullable = false)
    private Integer arrowTotal;

    @Column(name = "total_score", nullable = false)
    private Integer totalScore;

    @Column(name = "avg_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal avgScore;

    /** 系统预评是否达到通过标准（待复核结果） */
    @Column(name = "pass_flag", nullable = false)
    private Boolean passFlag;

    @Column(name = "eval_message", nullable = false, length = 500)
    private String evalMessage;

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

    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public Integer getEvalSeq() {
        return evalSeq;
    }

    public void setEvalSeq(Integer evalSeq) {
        this.evalSeq = evalSeq;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Integer getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(Integer ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public Integer getSnapGroupSize() {
        return snapGroupSize;
    }

    public void setSnapGroupSize(Integer snapGroupSize) {
        this.snapGroupSize = snapGroupSize;
    }

    public Integer getSnapMinRounds() {
        return snapMinRounds;
    }

    public void setSnapMinRounds(Integer snapMinRounds) {
        this.snapMinRounds = snapMinRounds;
    }

    public BigDecimal getSnapMinAverage() {
        return snapMinAverage;
    }

    public void setSnapMinAverage(BigDecimal snapMinAverage) {
        this.snapMinAverage = snapMinAverage;
    }

    public Integer getSnapValidityMonths() {
        return snapValidityMonths;
    }

    public void setSnapValidityMonths(Integer snapValidityMonths) {
        this.snapValidityMonths = snapValidityMonths;
    }

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Integer getRoundCount() {
        return roundCount;
    }

    public void setRoundCount(Integer roundCount) {
        this.roundCount = roundCount;
    }

    public Integer getArrowTotal() {
        return arrowTotal;
    }

    public void setArrowTotal(Integer arrowTotal) {
        this.arrowTotal = arrowTotal;
    }

    public Integer getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Integer totalScore) {
        this.totalScore = totalScore;
    }

    public BigDecimal getAvgScore() {
        return avgScore;
    }

    public void setAvgScore(BigDecimal avgScore) {
        this.avgScore = avgScore;
    }

    public Boolean getPassFlag() {
        return passFlag;
    }

    public void setPassFlag(Boolean passFlag) {
        this.passFlag = passFlag;
    }

    public String getEvalMessage() {
        return evalMessage;
    }

    public void setEvalMessage(String evalMessage) {
        this.evalMessage = evalMessage;
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
