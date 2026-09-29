package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 淘汰赛对阵场次。开赛后由后端一次性生成全部场次（不能靠前端临时拼接）。
 *
 * 首轮（round_no=1）主客队直接放种子队；后续轮两个槽位分别用 home_from_match_id /
 * away_from_match_id 指向来源场次，来源场次确认胜者后，后端在同一事务内把胜者自动带入。
 *
 * 状态机：PENDING 待开赛（队伍可能尚未齐，等晋级来源）→ ONGOING 进行中（已开始记局）
 *         → AWAIT_CONFIRM 规定局结束、待裁判确认胜者 → CONFIRMED 已确认（锁定，不可倒退）。
 * 阶段 stage：REGULATION 规定局 / SHOOTOFF 平分加赛箭。
 */
@Entity
@Table(name = "tournament_match")
public class TournamentMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 该轮场次序号，从 1 开始 */
    @Column(nullable = false)
    private Integer slot;

    /** 全赛事场次号：按轮次统一编号 */
    @Column(name = "match_no", nullable = false)
    private Integer matchNo;

    @Column(name = "home_team_id")
    private Long homeTeamId;

    @Column(name = "away_team_id")
    private Long awayTeamId;

    @Column(name = "winner_team_id")
    private Long winnerTeamId;

    /** PENDING / ONGOING / AWAIT_CONFIRM / CONFIRMED */
    @Column(nullable = false, length = 16)
    private String status;

    /** REGULATION / SHOOTOFF */
    @Column(nullable = false, length = 16)
    private String stage;

    @Column(name = "home_score", nullable = false)
    private Integer homeScore = 0;

    @Column(name = "away_score", nullable = false)
    private Integer awayScore = 0;

    @Column(name = "home_x_count", nullable = false)
    private Integer homeXCount = 0;

    @Column(name = "away_x_count", nullable = false)
    private Integer awayXCount = 0;

    /** 已进行的加赛轮数 */
    @Column(name = "shootoff_round_count", nullable = false)
    private Integer shootoffRoundCount = 0;

    @Column(name = "home_from_match_id")
    private Long homeFromMatchId;

    @Column(name = "away_from_match_id")
    private Long awayFromMatchId;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTournamentId() {
        return tournamentId;
    }

    public void setTournamentId(Long tournamentId) {
        this.tournamentId = tournamentId;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }

    public Integer getMatchNo() {
        return matchNo;
    }

    public void setMatchNo(Integer matchNo) {
        this.matchNo = matchNo;
    }

    public Long getHomeTeamId() {
        return homeTeamId;
    }

    public void setHomeTeamId(Long homeTeamId) {
        this.homeTeamId = homeTeamId;
    }

    public Long getAwayTeamId() {
        return awayTeamId;
    }

    public void setAwayTeamId(Long awayTeamId) {
        this.awayTeamId = awayTeamId;
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

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
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

    public Integer getShootoffRoundCount() {
        return shootoffRoundCount;
    }

    public void setShootoffRoundCount(Integer shootoffRoundCount) {
        this.shootoffRoundCount = shootoffRoundCount;
    }

    public Long getHomeFromMatchId() {
        return homeFromMatchId;
    }

    public void setHomeFromMatchId(Long homeFromMatchId) {
        this.homeFromMatchId = homeFromMatchId;
    }

    public Long getAwayFromMatchId() {
        return awayFromMatchId;
    }

    public void setAwayFromMatchId(Long awayFromMatchId) {
        this.awayFromMatchId = awayFromMatchId;
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
