package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.SafetyEventEquipment;

public interface SafetyEventEquipmentRepository extends JpaRepository<SafetyEventEquipment, Long> {

    List<SafetyEventEquipment> findByEventId(Long eventId);
}
