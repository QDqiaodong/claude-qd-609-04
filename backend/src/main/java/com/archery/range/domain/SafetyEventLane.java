package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 事件-箭道快照：记录箭道被锁定前的业务状态（prev_status）。
 * 锁定期 lanes.status = LOCKED，占用信息原样保留；放行时按 prev_status 恢复。
 */
@Entity
@Table(name = "safety_event_lane")
public class SafetyEventLane {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "lane_id", nullable = false)
    private Long laneId;

    /** 锁定前状态：OPEN / OCCUPIED / MAINTENANCE */
    @Column(name = "prev_status", nullable = false, length = 16)
    private String prevStatus;

    /** 放行时是否已恢复 */
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

    public Long getLaneId() {
        return laneId;
    }

    public void setLaneId(Long laneId) {
        this.laneId = laneId;
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
