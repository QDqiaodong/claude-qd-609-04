package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 器材租借影子记录：同一件器材在历史误操作中被分别挂在两张会员卡名下的异常登记。
 *
 * equipment.renter_id 是单值列，物理上一件器材只能有一个当前租约；
 * 但长期使用中可能留下「同一件器材两边都显示租出」的脏数据（迁移 / 换卡遗留）。
 * 重复档案合并预演用它与正式租约取交集，识别「双方租出同一件器材」的互斥；
 * 这不是正常业务写入路径，仅服务于异常排查与合并拦截。
 */
@Entity
@Table(name = "merge_equipment_rent_shadow")
public class EquipmentRentShadow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "equip_code", nullable = false, length = 24)
    private String equipCode;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 32)
    private String brand;

    @Column(name = "renter_id", nullable = false)
    private Long renterId;

    @Column(name = "rented_at", nullable = false)
    private LocalDateTime rentedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(Long equipmentId) {
        this.equipmentId = equipmentId;
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

    public Long getRenterId() {
        return renterId;
    }

    public void setRenterId(Long renterId) {
        this.renterId = renterId;
    }

    public LocalDateTime getRentedAt() {
        return rentedAt;
    }

    public void setRentedAt(LocalDateTime rentedAt) {
        this.rentedAt = rentedAt;
    }
}
