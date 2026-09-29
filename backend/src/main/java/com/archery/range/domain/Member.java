package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会员：会员卡号、姓名、手机号、等级（普通/银/金）、余额、注册日期、累计消费。
 *
 * status = MERGED 表示重复开卡合并后被封存的来源档案：原卡号 / 原姓名 / 原手机号原样保留可查，
 * 但不能再充值、开台、报名、租借或开始新回合；从旧卡号进入时跳转到 mergedIntoId 指向的保留档案。
 */
@Entity
@Table(name = "members")
public class Member {

    /** 在用 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已合并封存（历史档案，不可继续使用） */
    public static final String STATUS_MERGED = "MERGED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_no", nullable = false, unique = true, length = 24)
    private String cardNo;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal balance;

    @Column(name = "register_date", nullable = false)
    private LocalDate registerDate;

    @Column(name = "total_spend", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSpend;

    /** ACTIVE 在用 / MERGED 已合并封存 */
    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    /** 封存时合并去向的保留会员 id（来源档案跳转用） */
    @Column(name = "merged_into_id")
    private Long mergedIntoId;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "merge_reason", length = 500)
    private String mergeReason;

    /** 执行合并的值班经理 */
    @Column(name = "merged_by", length = 32)
    private String mergedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCardNo() {
        return cardNo;
    }

    public void setCardNo(String cardNo) {
        this.cardNo = cardNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public LocalDate getRegisterDate() {
        return registerDate;
    }

    public void setRegisterDate(LocalDate registerDate) {
        this.registerDate = registerDate;
    }

    public BigDecimal getTotalSpend() {
        return totalSpend;
    }

    public void setTotalSpend(BigDecimal totalSpend) {
        this.totalSpend = totalSpend;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getMergedIntoId() {
        return mergedIntoId;
    }

    public void setMergedIntoId(Long mergedIntoId) {
        this.mergedIntoId = mergedIntoId;
    }

    public LocalDateTime getMergedAt() {
        return mergedAt;
    }

    public void setMergedAt(LocalDateTime mergedAt) {
        this.mergedAt = mergedAt;
    }

    public String getMergeReason() {
        return mergeReason;
    }

    public void setMergeReason(String mergeReason) {
        this.mergeReason = mergeReason;
    }

    public String getMergedBy() {
        return mergedBy;
    }

    public void setMergedBy(String mergedBy) {
        this.mergedBy = mergedBy;
    }
}
