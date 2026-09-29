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

    List<MatchArrow> findByMemberId(Long memberId);

    List<MatchArrow> findByMatchId(Long matchId);

    Optional<MatchArrow> findByEndIdAndMemberIdAndShotIndex(Long endId, Long memberId, Integer shotIndex);

    void deleteByEndId(Long endId);

    /** 档案合并：把来源档案在淘汰赛局内的箭值记到保留档案名下（箭值本身不改写） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MatchArrow a set a.memberId = :keepId where a.memberId = :sourceId")
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
