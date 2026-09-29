package com.archery.range.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 加赛轮箭值：一轮每名队员各射一支，(round_id, member_id) 唯一。
 * 该轮锁定后整批冻结，不能再改。
 */
@Entity
@Table(name = "shootoff_arrow")
public class ShootoffArrow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

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

    public Long getRoundId() {
        return roundId;
    }

    public void setRoundId(Long roundId) {
        this.roundId = roundId;
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
