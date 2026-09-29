package com.archery.range.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会员：会员卡号、姓名、手机号、等级（普通/银/金）、余额、注册日期、累计消费。
 */
@Entity
@Table(name = "members")
public class Member {

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
}
