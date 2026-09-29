package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.CourseEnroll;

public interface CourseEnrollRepository extends JpaRepository<CourseEnroll, Long> {

    List<CourseEnroll> findByCourseIdOrderByIdAsc(Long courseId);

    List<CourseEnroll> findByMemberIdOrderByIdAsc(Long memberId);

    Optional<CourseEnroll> findByCourseIdAndMemberId(Long courseId, Long memberId);

    boolean existsByCourseIdAndMemberId(Long courseId, Long memberId);

    long countByCourseId(Long courseId);

    /**
     * 重复档案合并：把来源会员的课程报名迁移到保留会员名下（调用方先删除重复报名行）。
     * 原生 SQL，避免 JPQL 关联路径批量更新被 Hibernate 误译为更新主键。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update course_enroll set member_id = :keepId where member_id = :sourceId", nativeQuery = true)
    int reassignMember(@Param("sourceId") Long sourceId, @Param("keepId") Long keepId);
}
