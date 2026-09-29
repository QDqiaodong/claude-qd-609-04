package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.CertEvalRound;

public interface CertEvalRoundRepository extends JpaRepository<CertEvalRound, Long> {

    List<CertEvalRound> findByEvalId(Long evalId);

    List<CertEvalRound> findByEvalIdIn(List<Long> evalIds);
}
