package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MatchArrow;

public interface MatchArrowRepository extends JpaRepository<MatchArrow, Long> {

    List<MatchArrow> findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(Long endId);

    List<MatchArrow> findByMatchId(Long matchId);

    Optional<MatchArrow> findByEndIdAndMemberIdAndShotIndex(Long endId, Long memberId, Integer shotIndex);

    void deleteByEndId(Long endId);
}
