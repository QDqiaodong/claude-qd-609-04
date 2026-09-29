package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.TournamentTeam;

public interface TournamentTeamRepository extends JpaRepository<TournamentTeam, Long> {

    List<TournamentTeam> findByTournamentIdOrderByIdAsc(Long tournamentId);
}
