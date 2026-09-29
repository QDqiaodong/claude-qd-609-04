package com.archery.range.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.SafetyReview;

public interface SafetyReviewRepository extends JpaRepository<SafetyReview, Long> {

    List<SafetyReview> findByEventIdOrderByReviewRoundDescItemAsc(Long eventId);

    List<SafetyReview> findByEventIdAndReviewRound(Long eventId, Integer reviewRound);

    /**
     * 原子落结论：只有「待复核」的复核项才能写入结论。
     * 配合事件行悲观锁，多人同时复核同一项 / 重复点击，也只有一份最终结论；
     * 返回 0 表示该项已有结论，调用方据此拒绝重复提交。
     */
    @Modifying(flushAutomatically = true)
    @Query("update SafetyReview r set r.conclusion = :conclusion, r.reviewer = :reviewer,"
            + " r.reviewedAt = :reviewedAt, r.note = :note"
            + " where r.id = :id and r.conclusion is null")
    int concludeIfPending(@Param("id") Long id,
            @Param("conclusion") String conclusion,
            @Param("reviewer") String reviewer,
            @Param("reviewedAt") LocalDateTime reviewedAt,
            @Param("note") String note);
}
