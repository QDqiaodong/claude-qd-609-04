package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findAllByOrderByIdAsc();

    Optional<Member> findByCardNo(String cardNo);

    List<Member> findByLevelOrderByIdAsc(String level);

    boolean existsByPhone(String phone);

    boolean existsByCardNo(String cardNo);

    /**
     * 悲观写锁：同一会员的认证通过 / 撤回在此串行，
     * 保证同一适用范围的两笔申请并发通过时不会留下两条互相矛盾的有效认证。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
