package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.ShootoffArrow;

public interface ShootoffArrowRepository extends JpaRepository<ShootoffArrow, Long> {

    List<ShootoffArrow> findByRoundIdOrderByTeamIdAscMemberIdAsc(Long roundId);

    List<ShootoffArrow> findByMemberId(Long memberId);

    Optional<ShootoffArrow> findByRoundIdAndMemberId(Long roundId, Long memberId);

    void deleteByRoundId(Long roundId);

    /** 档案合并：把来源档案的加赛箭值记到保留档案名下（箭值本身不改写） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShootoffArrow a set a.memberId = :keepId where a.memberId = :sourceId")
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
