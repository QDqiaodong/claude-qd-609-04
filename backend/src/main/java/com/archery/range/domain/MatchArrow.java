package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 局内每支箭的箭值：X / 10 ~ 1 / M，环值口径与日常计分一致（X、10 均为 10，M 为 0）。
 * 未确认局允许按 (end_id, member_id, shot_index) 就地覆盖更正；局确认后整批冻结。
 */
@Entity
@Table(name = "match_arrow")
public class MatchArrow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "end_id", nullable = false)
    private Long endId;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 该局该队员第几支，0..arrows_per_end-1 */
    @Column(name = "shot_index", nullable = false)
    private Integer shotIndex;

    @Column(nullable = false, length = 4)
    private String ring;

    @Column(name = "ring_value", nullable = false)
    private Integer ringValue;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEndId() {
        return endId;
    }

    public void setEndId(Long endId) {
        this.endId = endId;
    }

    public Long getMatchId() {
        return matchId;
    }

    public void setMatchId(Long matchId) {
        this.matchId = matchId;
    }

    public Long getTeamId() {
        return teamId;
    }

    public void setTeamId(Long teamId) {
        this.teamId = teamId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public Integer getShotIndex() {
        return shotIndex;
    }

    public void setShotIndex(Integer shotIndex) {
        this.shotIndex = shotIndex;
    }

    public String getRing() {
        return ring;
    }

    public void setRing(String ring) {
        this.ring = ring;
    }

    public Integer getRingValue() {
        return ringValue;
    }

    public void setRingValue(Integer ringValue) {
        this.ringValue = ringValue;
    }
}
