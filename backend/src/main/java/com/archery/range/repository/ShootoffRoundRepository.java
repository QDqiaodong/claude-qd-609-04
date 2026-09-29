package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.ShootoffRound;

public interface ShootoffRoundRepository extends JpaRepository<ShootoffRound, Long> {

    List<ShootoffRound> findByMatchIdOrderByRoundNoAsc(Long matchId);

    Optional<ShootoffRound> findByMatchIdAndRoundNo(Long matchId, Integer roundNo);
}
