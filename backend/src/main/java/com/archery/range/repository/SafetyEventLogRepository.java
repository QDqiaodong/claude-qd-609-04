package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.SafetyEventLog;

public interface SafetyEventLogRepository extends JpaRepository<SafetyEventLog, Long> {

    List<SafetyEventLog> findByEventIdOrderByIdAsc(Long eventId);
}
