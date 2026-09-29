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

    List<TournamentTeamMember> findByMemberId(Long memberId);

    boolean existsByTournamentIdAndMemberId(Long tournamentId, Long memberId);

    long countByTeamId(Long teamId);

    void deleteByTeamId(Long teamId);

    /** 档案合并：把来源档案的赛事队员身份迁到保留档案（同赛事两队同时出现则唯一键报错、整体回滚） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TournamentTeamMember t set t.memberId = :keepId where t.memberId = :sourceId")
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
