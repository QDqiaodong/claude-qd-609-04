package com.archery.range.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 课程：课程名、教练、等级（初级/进阶/竞技）、上课时间、人数上限、已报名、教室/场地。
 */
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_name", nullable = false, length = 64)
    private String courseName;

    @Column(nullable = false, length = 32)
    private String coach;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(name = "class_time", nullable = false)
    private LocalDateTime classTime;

    @Column(nullable = false)
    private Integer capacity;

    @Column(nullable = false)
    private Integer enrolled;

    @Column(nullable = false, length = 32)
    private String venue;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getCoach() {
        return coach;
    }

    public void setCoach(String coach) {
        this.coach = coach;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public LocalDateTime getClassTime() {
        return classTime;
    }

    public void setClassTime(LocalDateTime classTime) {
        this.classTime = classTime;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getEnrolled() {
        return enrolled;
    }

    public void setEnrolled(Integer enrolled) {
        this.enrolled = enrolled;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }
}
