package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Lane;

import jakarta.persistence.LockModeType;

public interface LaneRepository extends JpaRepository<Lane, Long> {

    List<Lane> findAllByOrderByLaneNoAsc();

    List<Lane> findByStatusOrderByLaneNoAsc(String status);

    List<Lane> findByDistanceOrderByLaneNoAsc(Integer distance);

    List<Lane> findByOccupantId(Long occupantId);

    boolean existsByLaneNo(String laneNo);

    /** 悲观写锁：开台 / 收台 / 改状态与安全联锁的锁定、恢复互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lane l where l.id = :id")
    Optional<Lane> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁（按 id 升序，避免交叉加锁死锁）：停射事件批量锁定箭道时用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lane l where l.id in :ids order by l.id")
    List<Lane> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    /** 档案合并：把来源档案占用的箭道改挂保留档案（开台时间 / 预计时长不动） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Lane l set l.occupantId = :keepId, l.occupantName = :keepName"
            + " where l.occupantId = :sourceId and l.status = 'OCCUPIED'")
    int reassignOccupant(@Param("sourceId") Long sourceId,
            @Param("keepId") Long keepId,
            @Param("keepName") String keepName);
}
