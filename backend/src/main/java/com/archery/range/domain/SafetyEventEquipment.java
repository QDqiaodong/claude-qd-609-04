package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 事件-器材快照：记录器材被锁定前的业务状态（prev_status）。
 * 锁定期禁止租借 / 归还 / 切换维修；放行后按 prev_status 恢复，
 * 租出中的器材仍归原会员（renter 字段锁定期间不动）。
 */
@Entity
@Table(name = "safety_event_equipment")
public class SafetyEventEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** 锁定前状态：INSTOCK / RENTED / REPAIR */
    @Column(name = "prev_status", nullable = false, length = 16)
    private String prevStatus;

    @Column(nullable = false)
    private Boolean restored = false;

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

    public Long getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(Long equipmentId) {
        this.equipmentId = equipmentId;
    }

    public String getPrevStatus() {
        return prevStatus;
    }

    public void setPrevStatus(String prevStatus) {
        this.prevStatus = prevStatus;
    }

    public Boolean getRestored() {
        return restored;
    }

    public void setRestored(Boolean restored) {
        this.restored = restored;
    }
}
