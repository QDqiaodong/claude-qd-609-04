package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.CertEval;

public interface CertEvalRepository extends JpaRepository<CertEval, Long> {

    List<CertEval> findByApplicationIdOrderByEvalSeqDesc(Long applicationId);

    Optional<CertEval> findByApplicationIdAndEvalSeq(Long applicationId, Integer evalSeq);
}
