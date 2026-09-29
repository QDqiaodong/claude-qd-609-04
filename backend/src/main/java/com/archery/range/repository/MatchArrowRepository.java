package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.MatchArrow;

public interface MatchArrowRepository extends JpaRepository<MatchArrow, Long> {

    List<MatchArrow> findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(Long endId);

    List<MatchArrow> findByMatchId(Long matchId);

    Optional<MatchArrow> findByEndIdAndMemberIdAndShotIndex(Long endId, Long memberId, Integer shotIndex);

    void deleteByEndId(Long endId);

    /** 会员在已确认 / 录入中的局内箭值数（合并预演统计赛事参与） */
    long countByMemberId(Long memberId);

    /** 重复档案合并：局内箭值改记保留会员（箭值与顺序不改写） */
    @Modifying(flushAutomatically = true)
    @Query("update MatchArrow a set a.memberId = :keepId where a.memberId = :sourceId")
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
