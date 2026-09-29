package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.SafetyEventRound;

public interface SafetyEventRoundRepository extends JpaRepository<SafetyEventRound, Long> {

    List<SafetyEventRound> findByEventId(Long eventId);
}
