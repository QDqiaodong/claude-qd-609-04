package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 合并轨迹：预演生成、旧预演被取代、预演后数据变化失效、确认成功全程留痕。
 * 确认成功的明细（各业务关系迁移条数、去重报名、PB 重归组等）写入 detail，
 * 保留会员与被封存来源会员都能查到自己的合并结果；普通会员只读、不能发起或撤销。
 */
@Entity
@Table(name = "member_merge_log")
public class MemberMergeLog {

    public static final String ACTION_PREVIEW = "PREVIEW";
    public static final String ACTION_SUPERSEDE = "SUPERSEDE";
    public static final String ACTION_STALE = "STALE";
    public static final String ACTION_CONFIRM = "CONFIRM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merge_id", nullable = false)
    private Long mergeId;

    /** PREVIEW / SUPERSEDE / STALE / CONFIRM */
    @Column(nullable = false, length = 24)
    private String action;

    @Column(nullable = false, length = 32)
    private String operator;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public MemberMergeLog() {
    }

    public MemberMergeLog(Long mergeId, String action, String operator, String detail, LocalDateTime createdAt) {
        this.mergeId = mergeId;
        this.action = action;
        this.operator = operator;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMergeId() {
        return mergeId;
    }

    public void setMergeId(Long mergeId) {
        this.mergeId = mergeId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
