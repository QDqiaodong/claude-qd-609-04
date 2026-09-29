package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一场比赛的一局。确认（三名队员规定箭数全部录完）后不可改写；
 * 发现录入错误只能由裁判撤回「当前未结束比赛」的最后一局（物理删除本局并留轨迹）。
 */
@Entity
@Table(name = "match_end")
public class MatchEnd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    /** 局号，从 1 连续 */
    @Column(name = "end_no", nullable = false)
    private Integer endNo;

    @Column(name = "home_score", nullable = false)
    private Integer homeScore;

    @Column(name = "away_score", nullable = false)
    private Integer awayScore;

    @Column(name = "home_x_count", nullable = false)
    private Integer homeXCount;

    @Column(name = "away_x_count", nullable = false)
    private Integer awayXCount;

    /** DRAFT 录入中（可覆盖）/ CONFIRMED 已确认（冻结） */
    @Column(nullable = false, length = 12)
    private String status = "DRAFT";

    @Column(name = "confirmed_by", nullable = false, length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMatchId() {
        return matchId;
    }

    public void setMatchId(Long matchId) {
        this.matchId = matchId;
    }

    public Integer getEndNo() {
        return endNo;
    }

    public void setEndNo(Integer endNo) {
        this.endNo = endNo;
    }

    public Integer getHomeScore() {
        return homeScore;
    }

    public void setHomeScore(Integer homeScore) {
        this.homeScore = homeScore;
    }

    public Integer getAwayScore() {
        return awayScore;
    }

    public void setAwayScore(Integer awayScore) {
        this.awayScore = awayScore;
    }

    public Integer getHomeXCount() {
        return homeXCount;
    }

    public void setHomeXCount(Integer homeXCount) {
        this.homeXCount = homeXCount;
    }

    public Integer getAwayXCount() {
        return awayXCount;
    }

    public void setAwayXCount(Integer awayXCount) {
        this.awayXCount = awayXCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
