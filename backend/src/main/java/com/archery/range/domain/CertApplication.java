package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 弓种能力认证申请。
 *
 * 状态机：PENDING 待复核 → APPROVED 已通过 / REJECTED 已驳回 / NEED_MORE 待补充证据；
 * NEED_MORE 可补充证据重新提交（回到 PENDING，evalSeq + 1，历史评定保留）；
 * APPROVED 可被 WITHDRAWN 撤回，或被同一适用范围（会员 + 弓种 + 射距）的新认证 SUPERSEDED 取代。
 * 过期不改写状态：APPROVED 且 validUntil 已过即视为已过期，任何入口都不得再当有效认证。
 */
@Entity
@Table(name = "cert_application")
public class CertApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_no", nullable = false, unique = true, length = 24)
    private String appNo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "bow_type", nullable = false, length = 16)
    private String bowType;

    /** 申请射距（米） */
    @Column(nullable = false)
    private Integer distance;

    /** 证据窗口起止（明确时间范围） */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** PENDING / APPROVED / REJECTED / NEED_MORE / WITHDRAWN / SUPERSEDED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 当前评定序号：补充证据重新评定 +1 */
    @Column(name = "eval_seq", nullable = false)
    private Integer evalSeq;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "withdrawn_by", length = 32)
    private String withdrawnBy;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "withdraw_reason", length = 500)
    private String withdrawReason;

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

    public String getAppNo() {
        return appNo;
    }

    public void setAppNo(String appNo) {
        this.appNo = appNo;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getEvalSeq() {
        return evalSeq;
    }

    public void setEvalSeq(Integer evalSeq) {
        this.evalSeq = evalSeq;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDateTime validUntil) {
        this.validUntil = validUntil;
    }

    public String getWithdrawnBy() {
        return withdrawnBy;
    }

    public void setWithdrawnBy(String withdrawnBy) {
        this.withdrawnBy = withdrawnBy;
    }

    public LocalDateTime getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(LocalDateTime withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public String getWithdrawReason() {
        return withdrawReason;
    }

    public void setWithdrawReason(String withdrawReason) {
        this.withdrawReason = withdrawReason;
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
