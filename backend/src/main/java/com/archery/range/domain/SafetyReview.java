package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 分项复核：每个事件每一轮有 RANGE（教练确认射线与人员安全）与
 * EQUIPMENT（器材管理员确认器材已检查）两项。
 * (event_id, review_round, item) 有唯一约束，同一复核项只能形成一份最终结论；
 * 不通过时 note 必填原因，结论随轮次永久保留。
 */
@Entity
@Table(name = "safety_review")
public class SafetyReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "review_round", nullable = false)
    private Integer reviewRound;

    /** RANGE 射线与人员安全 / EQUIPMENT 器材检查 */
    @Column(nullable = false, length = 16)
    private String item;

    /** PASS 通过 / FAIL 不通过；NULL 为待复核 */
    @Column(length = 8)
    private String conclusion;

    @Column(length = 32)
    private String reviewer;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 复核意见；不通过时必填原因 */
    @Column(length = 500)
    private String note;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public Integer getReviewRound() {
        return reviewRound;
    }

    public void setReviewRound(Integer reviewRound) {
        this.reviewRound = reviewRound;
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
