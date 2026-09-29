package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 参赛队。开赛时按建队顺序固定种子位 seed_no（1..team_size），
 * 种子位决定首轮对阵与后续轮的晋级走向，开赛后不可替换。
 */
@Entity
@Table(name = "tournament_team")
public class TournamentTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "team_name", nullable = false, length = 48)
    private String teamName;

    /** 种子位（开赛后固定） */
    @Column(name = "seed_no")
    private Integer seedNo;

    /** 最终名次：冠军=1 */
    @Column(name = "final_rank")
    private Integer finalRank;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public Integer getSeedNo() {
        return seedNo;
    }

    public void setSeedNo(Integer seedNo) {
        this.seedNo = seedNo;
    }

    public Integer getFinalRank() {
        return finalRank;
    }

    public void setFinalRank(Integer finalRank) {
        this.finalRank = finalRank;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
