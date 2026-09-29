package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.TournamentTeamMember;

public interface TournamentTeamMemberRepository extends JpaRepository<TournamentTeamMember, Long> {

    List<TournamentTeamMember> findByTeamIdOrderByPositionAsc(Long teamId);

    List<TournamentTeamMember> findByTournamentIdOrderByTeamIdAscPositionAsc(Long tournamentId);

    /** 会员在各赛事的队员身份（合并预演使用：两侧在同一赛事各在一队即互斥） */
    List<TournamentTeamMember> findByMemberIdOrderByTournamentIdAsc(Long memberId);

    boolean existsByTournamentIdAndMemberId(Long tournamentId, Long memberId);

    long countByTeamId(Long teamId);

    void deleteByTeamId(Long teamId);

    /** 重复档案合并：队-会员身份改记保留会员（站位不变；互斥已在预演中拦截） */
    @Modifying(flushAutomatically = true)
    @Query("update TournamentTeamMember t set t.memberId = :keepId where t.memberId = :sourceId")
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
