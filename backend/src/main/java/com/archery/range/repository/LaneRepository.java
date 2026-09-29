package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Lane;

import jakarta.persistence.LockModeType;

public interface LaneRepository extends JpaRepository<Lane, Long> {

    List<Lane> findAllByOrderByLaneNoAsc();

    List<Lane> findByStatusOrderByLaneNoAsc(String status);

    List<Lane> findByDistanceOrderByLaneNoAsc(Integer distance);

    boolean existsByLaneNo(String laneNo);

    /** 悲观写锁：开台 / 收台 / 改状态与安全联锁的锁定、恢复互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lane l where l.id = :id")
    Optional<Lane> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁（按 id 升序，避免交叉加锁死锁）：停射事件批量锁定箭道时用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lane l where l.id in :ids order by l.id")
    List<Lane> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
