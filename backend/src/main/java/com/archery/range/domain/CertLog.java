package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 认证状态轨迹：建立 / 系统预评 / 通过 / 驳回 / 要求补充 / 补充重评 / 撤回 / 被取代，全程留痕。
 */
@Entity
@Table(name = "cert_log")
public class CertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    /** CREATE / EVAL / APPROVE / REJECT / NEED_MORE / RESUBMIT / WITHDRAW / SUPERSEDE */
    @Column(nullable = false, length = 24)
    private String action;

    @Column(nullable = false, length = 32)
    private String operator;

    /** COACH 教练 / SYSTEM 认证系统 */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(length = 500)
    private String detail;

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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
