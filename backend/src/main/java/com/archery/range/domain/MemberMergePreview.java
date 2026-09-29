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
 * 重复会员档案合并预演版本。
 *
 * 值班经理选定保留档案（keep）与来源档案（source）后生成一份预演；
 * 确认合并必须以仍为 PREVIEWED 的预演版本为依据：
 * <ul>
 *   <li>两侧业务数据指纹（余额 / 报名 / 回合 / 租借 / 认证）在确认时重算比对，
 *       预演后任一变化都拒绝确认，要求重新预演；</li>
 *   <li>确认成功时把预演原子消费为 CONSUMED，两个操作端同时确认同一来源档案只成功一次。</li>
 * </ul>
 */
@Entity
@Table(name = "member_merge_preview")
public class MemberMergePreview {

    /** 待确认 */
    public static final String STATUS_PREVIEWED = "PREVIEWED";
    /** 已用于一次成功合并（一次性） */
    public static final String STATUS_CONSUMED = "CONSUMED";
    /** 同一对会员又产生了新预演，旧预演作废 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "preview_no", nullable = false, unique = true, length = 24)
    private String previewNo;

    @Column(name = "keep_member_id", nullable = false)
    private Long keepMemberId;

    @Column(name = "source_member_id", nullable = false)
    private Long sourceMemberId;

    @Column(nullable = false, length = 32)
    private String operator;

    @Column(nullable = false, length = 500)
    private String reason;

    /** PREVIEWED / CONSUMED / SUPERSEDED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "keep_fingerprint", nullable = false, length = 64)
    private String keepFingerprint;

    @Column(name = "source_fingerprint", nullable = false, length = 64)
    private String sourceFingerprint;

    /** 预演结论快照 JSON（两侧清单 / 互斥项 / 去重项 / 合并后金额等级） */
    @Lob
    @Column(name = "snapshot_json", nullable = false)
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_by", length = 32)
    private String consumedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPreviewNo() {
        return previewNo;
    }

    public void setPreviewNo(String previewNo) {
        this.previewNo = previewNo;
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

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
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

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }

    public String getConsumedBy() {
        return consumedBy;
    }

    public void setConsumedBy(String consumedBy) {
        this.consumedBy = consumedBy;
    }
}
