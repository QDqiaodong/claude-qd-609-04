package com.archery.range.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.ArrowScore;

/**
 * 箭支成绩表本身的仓库。日常写入走 Round 的有序集合级联，
 * 这里只留一个按环数统计的派生查询给看板用。
 */
public interface ArrowScoreRepository extends JpaRepository<ArrowScore, Long> {

    long countByRing(String ring);
}
