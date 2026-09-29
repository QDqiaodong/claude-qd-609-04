package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Equipment;

import jakarta.persistence.LockModeType;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    List<Equipment> findAllByOrderByEquipCodeAsc();

    List<Equipment> findByTypeOrderByEquipCodeAsc(String type);

    List<Equipment> findByStatusOrderByEquipCodeAsc(String status);

    boolean existsByEquipCode(String equipCode);

    /** 悲观写锁：租借 / 归还 / 报修与安全联锁的锁定、恢复互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁（按 id 升序）：停射事件批量锁定器材时用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id in :ids order by e.id")
    List<Equipment> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
