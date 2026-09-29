package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 平分后的加赛轮：一轮每名队员各射一支。
 * 先比较两队加赛总分，再比较 X 数量；仍相同则 winner 为空、再开下一轮，直到产生唯一胜者。
 * 轮状态：DRAFT 录入中（可覆盖）→ LOCKED 已锁定（不可改写）。
 */
@Entity
@Table(name = "shootoff_round")
public class ShootoffRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "home_score", nullable = false)
    private Integer homeScore = 0;

    @Column(name = "away_score", nullable = false)
    private Integer awayScore = 0;

    @Column(name = "home_x_count", nullable = false)
    private Integer homeXCount = 0;

    @Column(name = "away_x_count", nullable = false)
    private Integer awayXCount = 0;

    /** 本轮产生的胜者；NULL 表示仍平、需继续加赛 */
    @Column(name = "winner_team_id")
    private Long winnerTeamId;

    /** DRAFT / LOCKED */
    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "locked_by", length = 32)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

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

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
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

    public Long getWinnerTeamId() {
        return winnerTeamId;
    }

    public void setWinnerTeamId(Long winnerTeamId) {
        this.winnerTeamId = winnerTeamId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }
}
