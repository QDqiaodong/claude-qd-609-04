package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.SafetyEventLane;

public interface SafetyEventLaneRepository extends JpaRepository<SafetyEventLane, Long> {

    List<SafetyEventLane> findByEventId(Long eventId);
}
