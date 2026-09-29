package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.ShootoffArrow;

public interface ShootoffArrowRepository extends JpaRepository<ShootoffArrow, Long> {

    List<ShootoffArrow> findByRoundIdOrderByTeamIdAscMemberIdAsc(Long roundId);

    Optional<ShootoffArrow> findByRoundIdAndMemberId(Long roundId, Long memberId);

    void deleteByRoundId(Long roundId);
}
