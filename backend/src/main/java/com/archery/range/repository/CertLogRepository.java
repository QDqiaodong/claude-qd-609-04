package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.CertLog;

public interface CertLogRepository extends JpaRepository<CertLog, Long> {

    List<CertLog> findByApplicationIdOrderByIdAsc(Long applicationId);
}
