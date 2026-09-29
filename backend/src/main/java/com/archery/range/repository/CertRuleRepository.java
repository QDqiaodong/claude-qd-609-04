package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.CertRule;

public interface CertRuleRepository extends JpaRepository<CertRule, Long> {

    List<CertRule> findAllByOrderByRuleCodeAscVersionDesc();

    /** 新申请只能选现行规则（每个谱系最多一条 ACTIVE） */
    List<CertRule> findByStatusOrderByBowTypeAscDistanceAsc(String status);

    List<CertRule> findByRuleCodeOrderByVersionDesc(String ruleCode);

    Optional<CertRule> findFirstByRuleCodeOrderByVersionDesc(String ruleCode);

    boolean existsByRuleCode(String ruleCode);

    /** 同一弓种 + 射距是否已存在规则谱系（再调标准走「发布新版本」） */
    boolean existsByBowTypeAndDistance(String bowType, Integer distance);
}
