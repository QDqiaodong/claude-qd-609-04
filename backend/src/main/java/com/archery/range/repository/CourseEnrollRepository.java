package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.CourseEnroll;

public interface CourseEnrollRepository extends JpaRepository<CourseEnroll, Long> {

    List<CourseEnroll> findByCourseIdOrderByIdAsc(Long courseId);

    List<CourseEnroll> findByMemberIdOrderByIdAsc(Long memberId);

    Optional<CourseEnroll> findByCourseIdAndMemberId(Long courseId, Long memberId);

    boolean existsByCourseIdAndMemberId(Long courseId, Long memberId);

    long countByCourseId(Long courseId);
}
