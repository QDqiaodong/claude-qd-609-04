package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Tournament;

import jakarta.persistence.LockModeType;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    /** 悲观写锁：开赛 / 确认胜者推进对阵等写操作在此串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tournament t where t.id = :id")
    Optional<Tournament> findByIdForUpdate(@Param("id") Long id);

    List<Tournament> findAllByOrderByIdDesc();

    long countByStatus(String status);
}
