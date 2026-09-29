package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.TournamentMatch;

import jakarta.persistence.LockModeType;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, Long> {

    /** 悲观写锁：记局 / 撤回 / 确认胜者与自动晋级在此串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TournamentMatch m where m.id = :id")
    Optional<TournamentMatch> findByIdForUpdate(@Param("id") Long id);

    List<TournamentMatch> findByTournamentIdOrderByRoundNoAscSlotAsc(Long tournamentId);

    Optional<TournamentMatch> findByTournamentIdAndRoundNoAndSlot(Long tournamentId, Integer roundNo, Integer slot);

    List<TournamentMatch> findByTournamentIdAndRoundNoOrderBySlotAsc(Long tournamentId, Integer roundNo);

    /** 用于自动带入晋级队：按来源场次找下一轮场次 */
    List<TournamentMatch> findByHomeFromMatchIdOrAwayFromMatchId(Long homeFromMatchId, Long awayFromMatchId);
}
