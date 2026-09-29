package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 安全停射事件：值班经理发现异常时建立，立即联锁受影响箭道 / 回合 / 器材。
 *
 * 状态机：PENDING 待处置 → REVIEWING 分项复核 → CLEARED 待放行 → RELEASED 已放行。
 * 分项复核须教练（射线与人员安全）与器材管理员（器材检查）双双通过；
 * 任一不通过退回 PENDING 并继续锁定，重新发起复核时 review_round +1。
 */
@Entity
@Table(name = "safety_event")
public class SafetyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_no", nullable = false, unique = true, length = 24)
    private String eventNo;

    /** LANE_DEVICE 箭道设备异常 / PERSON_INTRUSION 人员闯入射线 / EQUIP_SUSPECT 疑似器材故障 / OTHER 其他 */
    @Column(nullable = false, length = 24)
    private String reason;

    /** NOTICE 一般 / MAJOR 严重 / CRITICAL 紧急 */
    @Column(nullable = false, length = 16)
    private String severity;

    /** 现场说明 */
    @Column(nullable = false, length = 500)
    private String description;

    /** PENDING / REVIEWING / CLEARED / RELEASED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 复核轮次：复核不通过退回后重新发起会 +1，历史结论按轮次保留 */
    @Column(name = "review_round", nullable = false)
    private Integer reviewRound;

    /** 创建人（值班经理），不能代替两个复核角色签字 */
    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "released_by", length = 32)
    private String releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventNo() {
        return eventNo;
    }

    public void setEventNo(String eventNo) {
        this.eventNo = eventNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getReviewRound() {
        return reviewRound;
    }

    public void setReviewRound(Integer reviewRound) {
        this.reviewRound = reviewRound;
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

    public String getReleasedBy() {
        return releasedBy;
    }

    public void setReleasedBy(String releasedBy) {
        this.releasedBy = releasedBy;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }
}
