package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.SafetyEvent;

import jakarta.persistence.LockModeType;

public interface SafetyEventRepository extends JpaRepository<SafetyEvent, Long> {

    /**
     * 悲观写锁（SELECT ... FOR UPDATE）：复核、放行等写操作先锁住事件行，
     * 多人同时复核 / 重复点击 / 两个窗口同时放行都会在此串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SafetyEvent e where e.id = :id")
    Optional<SafetyEvent> findByIdForUpdate(@Param("id") Long id);

    boolean existsByEventNo(String eventNo);

    long countByStatusNot(String status);

    List<SafetyEvent> findAllByOrderByIdDesc();

    List<SafetyEvent> findByStatusNotOrderByIdDesc(String status);
}
