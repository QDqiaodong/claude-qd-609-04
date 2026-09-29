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
 * 器材：编号、类型（反曲弓/复合弓/传统弓/护具/箭支）、品牌、租金（元/小时）、状态。
 */
@Entity
@Table(name = "equipment")
public class Equipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equip_code", nullable = false, unique = true, length = 24)
    private String equipCode;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 32)
    private String brand;

    @Column(name = "rent_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal rentPrice;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "renter_id")
    private Long renterId;

    @Column(name = "renter_name", length = 32)
    private String renterName;

    @Column(name = "rented_at")
    private LocalDateTime rentedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEquipCode() {
        return equipCode;
    }

    public void setEquipCode(String equipCode) {
        this.equipCode = equipCode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public BigDecimal getRentPrice() {
        return rentPrice;
    }

    public void setRentPrice(BigDecimal rentPrice) {
        this.rentPrice = rentPrice;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRenterId() {
        return renterId;
    }

    public void setRenterId(Long renterId) {
        this.renterId = renterId;
    }

    public String getRenterName() {
        return renterName;
    }

    public void setRenterName(String renterName) {
        this.renterName = renterName;
    }

    public LocalDateTime getRentedAt() {
        return rentedAt;
    }

    public void setRentedAt(LocalDateTime rentedAt) {
        this.rentedAt = rentedAt;
    }
}
