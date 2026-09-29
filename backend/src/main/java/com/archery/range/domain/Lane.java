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
 * 箭道：道号、距离（10/18/30/50 米）、箭靶类型、状态、每小时单价。
 * 开台后记录占用会员与预计时长，收台时清空。
 */
@Entity
@Table(name = "lanes")
public class Lane {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lane_no", nullable = false, unique = true, length = 16)
    private String laneNo;

    @Column(nullable = false)
    private Integer distance;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "hourly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyPrice;

    @Column(name = "occupant_id")
    private Long occupantId;

    @Column(name = "occupant_name", length = 32)
    private String occupantName;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "planned_hours")
    private Integer plannedHours;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLaneNo() {
        return laneNo;
    }

    public void setLaneNo(String laneNo) {
        this.laneNo = laneNo;
    }

    public Integer getDistance() {
        return distance;
    }

    public void setDistance(Integer distance) {
        this.distance = distance;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getHourlyPrice() {
        return hourlyPrice;
    }

    public void setHourlyPrice(BigDecimal hourlyPrice) {
        this.hourlyPrice = hourlyPrice;
    }

    public Long getOccupantId() {
        return occupantId;
    }

    public void setOccupantId(Long occupantId) {
        this.occupantId = occupantId;
    }

    public String getOccupantName() {
        return occupantName;
    }

    public void setOccupantName(String occupantName) {
        this.occupantName = occupantName;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }

    public Integer getPlannedHours() {
        return plannedHours;
    }

    public void setPlannedHours(Integer plannedHours) {
        this.plannedHours = plannedHours;
    }
}
