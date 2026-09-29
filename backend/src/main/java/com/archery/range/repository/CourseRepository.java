package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.Course;

public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findAllByOrderByClassTimeAsc();

    List<Course> findByLevelOrderByClassTimeAsc(String level);
}
