package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 重复会员档案合并单。
 *
 * 值班经理先发起预演：预演把两张卡的余额、累计消费、等级、课程报名、未结束计分回合、
 * 历史成绩、当前器材租借、认证申请与有效认证逐项列出，给出互斥项与合并结果，
 * 并固化双方数据指纹与完整快照。确认合并必须以预演版本为依据：
 * 任一会员的余额 / 报名 / 回合 / 租借 / 认证在预演后发生变化，指纹不符即拒绝并要求重新预演。
 *
 * 状态机：
 * PREVIEWED 预演完成待确认 → CONFIRMED 已执行；
 * 同一对档案再次预演时旧预演转 SUPERSEDED；确认时发现数据已变化转 STALE。
 */
@Entity
@Table(name = "member_merge")
public class MemberMerge {

    public static final String STATUS_PREVIEWED = "PREVIEWED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_STALE = "STALE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_no", nullable = false, unique = true, length = 24)
    private String mergeNo;

    @Column(name = "keep_member_id", nullable = false)
    private Long keepMemberId;

    @Column(name = "source_member_id", nullable = false)
    private Long sourceMemberId;

    @Column(nullable = false, length = 500)
    private String reason;

    /** PREVIEWED / CONFIRMED / SUPERSEDED / STALE */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "preview_operator", nullable = false, length = 32)
    private String previewOperator;

    @Column(name = "previewed_at", nullable = false)
    private LocalDateTime previewedAt;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 预演时保留档案的数据指纹（确认时必须一致） */
    @Lob
    @Column(name = "keep_fingerprint", nullable = false)
    private String keepFingerprint;

    /** 预演时来源档案的数据指纹（确认时必须一致） */
    @Lob
    @Column(name = "source_fingerprint", nullable = false)
    private String sourceFingerprint;

    /** 预演完整结果快照（JSON）：确认操作的唯一依据 */
    @Lob
    @Column(nullable = false)
    private String snapshot;

    /** 预演互斥项清单（JSON，无互斥项时为空数组） */
    @Lob
    @Column
    private String conflicts;

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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPreviewOperator() {
        return previewOperator;
    }

    public void setPreviewOperator(String previewOperator) {
        this.previewOperator = previewOperator;
    }

    public LocalDateTime getPreviewedAt() {
        return previewedAt;
    }

    public void setPreviewedAt(LocalDateTime previewedAt) {
        this.previewedAt = previewedAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getKeepFingerprint() {
        return keepFingerprint;
    }

    public void setKeepFingerprint(String keepFingerprint) {
        this.keepFingerprint = keepFingerprint;
    }

    public String getSourceFingerprint() {
        return sourceFingerprint;
    }

    public void setSourceFingerprint(String sourceFingerprint) {
        this.sourceFingerprint = sourceFingerprint;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    public String getConflicts() {
        return conflicts;
    }

    public void setConflicts(String conflicts) {
        this.conflicts = conflicts;
    }
}
