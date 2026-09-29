package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 事件-回合快照：受影响箭道上进行中的计分回合被暂停（PAUSED）。
 * 暂停期间保留已记录箭支的顺序与分数，但不能记箭 / 撤销 / 提交；
 * 放行后恢复 ONGOING，从原进度继续。
 */
@Entity
@Table(name = "safety_event_round")
public class SafetyEventRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    /** 锁定前状态：ONGOING */
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

    public Long getRoundId() {
        return roundId;
    }

    public void setRoundId(Long roundId) {
        this.roundId = roundId;
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
