package com.archery.range.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.archery.range.domain.MemberMergeLog;

public interface MemberMergeLogRepository extends JpaRepository<MemberMergeLog, Long> {

    boolean existsByMergeNo(String mergeNo);

    List<MemberMergeLog> findByKeepMemberIdOrderByMergedAtDescIdDesc(Long keepMemberId);

    List<MemberMergeLog> findBySourceMemberIdOrderByMergedAtDescIdDesc(Long sourceMemberId);

    /** 与某会员相关的全部合并轨迹（作为保留档案或来源档案；会员本人只看自己的） */
    List<MemberMergeLog> findByKeepMemberIdOrSourceMemberIdOrderByMergedAtDescIdDesc(Long keepMemberId, Long sourceMemberId);
}
