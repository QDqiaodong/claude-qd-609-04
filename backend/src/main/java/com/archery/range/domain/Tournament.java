package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 馆内团体淘汰赛赛事。
 *
 * 状态机：DRAFT 报名中（值班经理可调整参赛队与队员）→ ONGOING 已开赛（队伍与对阵冻结，
 * 后端一次性生成完整淘汰对阵）→ FINISHED 已完赛（决赛胜者确认）。
 */
@Entity
@Table(name = "tournament")
public class Tournament {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 参赛队数：4 或 8 */
    @Column(name = "team_size", nullable = false)
    private Integer teamSize;

    /** 每场局数 */
    @Column(name = "ends_per_match", nullable = false)
    private Integer endsPerMatch;

    /** 每名队员每局规定箭数 */
    @Column(name = "arrows_per_end", nullable = false)
    private Integer arrowsPerEnd;

    /** DRAFT / ONGOING / FINISHED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 当前轮次：最早存在未确认场次的那一轮，开赛时为 1 */
    @Column(name = "current_round")
    private Integer currentRound;

    @Column(name = "champion_team_id")
    private Long championTeamId;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTeamSize() {
        return teamSize;
    }

    public void setTeamSize(Integer teamSize) {
        this.teamSize = teamSize;
    }

    public Integer getEndsPerMatch() {
        return endsPerMatch;
    }

    public void setEndsPerMatch(Integer endsPerMatch) {
        this.endsPerMatch = endsPerMatch;
    }

    public Integer getArrowsPerEnd() {
        return arrowsPerEnd;
    }

    public void setArrowsPerEnd(Integer arrowsPerEnd) {
        this.arrowsPerEnd = arrowsPerEnd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(Integer currentRound) {
        this.currentRound = currentRound;
    }

    public Long getChampionTeamId() {
        return championTeamId;
    }

    public void setChampionTeamId(Long championTeamId) {
        this.championTeamId = championTeamId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
