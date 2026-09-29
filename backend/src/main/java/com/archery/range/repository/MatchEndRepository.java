package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MatchEnd;

public interface MatchEndRepository extends JpaRepository<MatchEnd, Long> {

    /** 已确认局按局号排列（局分明细、规定局总分重算都只认已确认局） */
    List<MatchEnd> findByMatchIdAndStatusOrderByEndNoAsc(Long matchId, String status);

    Optional<MatchEnd> findByMatchIdAndEndNo(Long matchId, Integer endNo);

    /** 取最大局号（含录入中的局），用于开新局时算局号；空表时 Optional 为空 */
    Optional<MatchEnd> findTopByMatchIdOrderByEndNoDesc(Long matchId);

    /** 一场比赛至多有一个录入中（DRAFT）的局 */
    Optional<MatchEnd> findByMatchIdAndStatus(Long matchId, String status);

    long countByMatchId(Long matchId);

    long countByMatchIdAndStatus(Long matchId, String status);
}
