package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.TournamentLog;

public interface TournamentLogRepository extends JpaRepository<TournamentLog, Long> {

    List<TournamentLog> findByTournamentIdOrderByIdAsc(Long tournamentId);
}
