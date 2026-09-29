package com.archery.range.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.MemberMergePreview;

import jakarta.persistence.LockModeType;

public interface MemberMergePreviewRepository extends JpaRepository<MemberMergePreview, Long> {

    boolean existsByPreviewNo(String previewNo);

    /** 悲观写锁：两端并发确认同一预演在此串行，只有一端能消费成功 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MemberMergePreview p where p.id = :id")
    Optional<MemberMergePreview> findByIdForUpdate(@Param("id") Long id);

    /** 同一来源档案仍待确认的预演（新预演产生时把旧的标记作废 / 合并前校验来源是否已被处理） */
    List<MemberMergePreview> findBySourceMemberIdAndStatusOrderByIdDesc(Long sourceMemberId, String status);

    /**
     * 原子消费预演：只有仍是 PREVIEWED 的预演能被确认。
     * 两个操作端同时确认同一来源档案时，只有一条 UPDATE 生效（返回 1），
     * 另一端返回 0，据此提示「档案已被处理」并指引到保留档案，绝不重复累加余额或重复迁移。
     */
    @Modifying(flushAutomatically = true)
    @Query("update MemberMergePreview p set p.status = 'CONSUMED', p.consumedAt = :now, p.consumedBy = :operator"
            + " where p.id = :id and p.status = 'PREVIEWED'")
    int consumeIfPreviewed(@Param("id") Long id,
            @Param("operator") String operator,
            @Param("now") LocalDateTime now);

    /** 同一对会员产生新预演时，旧的待确认预演作废，确认时会要求重新预演 */
    @Modifying(flushAutomatically = true)
    @Query("update MemberMergePreview p set p.status = 'SUPERSEDED'"
            + " where p.keepMemberId = :keepId and p.sourceMemberId = :sourceId and p.status = 'PREVIEWED'")
    int supersedeOldPreviews(@Param("keepId") Long keepId, @Param("sourceId") Long sourceId);
}
