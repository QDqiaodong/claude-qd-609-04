package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MemberMergeLog;

public interface MemberMergeLogRepository extends JpaRepository<MemberMergeLog, Long> {

    List<MemberMergeLog> findByMergeIdOrderByIdAsc(Long mergeId);
}
