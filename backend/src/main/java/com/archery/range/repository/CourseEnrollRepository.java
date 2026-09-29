package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.CourseEnroll;
import com.archery.range.domain.Member;

public interface CourseEnrollRepository extends JpaRepository<CourseEnroll, Long> {

    List<CourseEnroll> findByCourseIdOrderByIdAsc(Long courseId);

    List<CourseEnroll> findByMemberIdOrderByIdAsc(Long memberId);

    Optional<CourseEnroll> findByCourseIdAndMemberId(Long courseId, Long memberId);

    boolean existsByCourseIdAndMemberId(Long courseId, Long memberId);

    long countByCourseId(Long courseId);

    /** 档案合并：把来源档案指定的报名记录挂到保留档案名下（已排除与保留档案重复的课程） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CourseEnroll e set e.member = :keep where e.id in :enrollIds and e.member = :source")
    int reassignMembers(@Param("enrollIds") List<Long> enrollIds,
            @Param("keep") Member keep,
            @Param("source") Member source);
}
