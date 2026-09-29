package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.TournamentTeamMember;

public interface TournamentTeamMemberRepository extends JpaRepository<TournamentTeamMember, Long> {

    List<TournamentTeamMember> findByTeamIdOrderByPositionAsc(Long teamId);

    List<TournamentTeamMember> findByTournamentIdOrderByTeamIdAscPositionAsc(Long tournamentId);

    boolean existsByTournamentIdAndMemberId(Long tournamentId, Long memberId);

    long countByTeamId(Long teamId);

    void deleteByTeamId(Long teamId);
}
