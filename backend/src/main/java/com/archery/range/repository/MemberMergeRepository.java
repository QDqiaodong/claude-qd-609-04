package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.MemberMerge;

import jakarta.persistence.LockModeType;

public interface MemberMergeRepository extends JpaRepository<MemberMerge, Long> {

    boolean existsByMergeNo(String mergeNo);

    List<MemberMerge> findByKeepMemberIdOrSourceMemberIdOrderByIdDesc(Long keepMemberId, Long sourceMemberId);

    List<MemberMerge> findByKeepMemberIdOrderByIdDesc(Long keepMemberId);

    List<MemberMerge> findBySourceMemberIdOrderByIdDesc(Long sourceMemberId);

    /**
     * 悲观写锁：两个操作端同时确认同一来源档案时在此串行，
     * 配合状态条件更新保证同一预演只能被确认一次。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MemberMerge m where m.id = :id")
    Optional<MemberMerge> findByIdForUpdate(@Param("id") Long id);

    /** 该来源档案是否已存在确认成功的合并（并发兜底：后到方必须看到档案已被处理） */
    @Query("select count(m) from MemberMerge m where m.sourceMemberId = :sourceMemberId and m.status = 'CONFIRMED'")
    long countConfirmedBySource(@Param("sourceMemberId") Long sourceMemberId);
}
