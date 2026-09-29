package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.EquipmentRentShadow;

public interface EquipmentRentShadowRepository extends JpaRepository<EquipmentRentShadow, Long> {

    List<EquipmentRentShadow> findByRenterIdOrderByEquipmentIdAsc(Long renterId);
}
