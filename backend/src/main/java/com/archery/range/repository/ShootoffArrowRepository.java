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

    Optional<ShootoffArrow> findByRoundIdAndMemberId(Long roundId, Long memberId);

    void deleteByRoundId(Long roundId);

    /** 会员的加赛箭值数（合并预演统计赛事参与） */
    long countByMemberId(Long memberId);

    /** 重复档案合并：加赛箭值改记保留会员（箭值不改写） */
    @Modifying(flushAutomatically = true)
    @Query("update ShootoffArrow a set a.memberId = :keepId where a.memberId = :sourceId")
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
