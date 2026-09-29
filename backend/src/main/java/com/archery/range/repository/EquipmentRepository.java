package com.archery.range.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.Equipment;

import jakarta.persistence.LockModeType;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    List<Equipment> findAllByOrderByEquipCodeAsc();

    List<Equipment> findByTypeOrderByEquipCodeAsc(String type);

    List<Equipment> findByStatusOrderByEquipCodeAsc(String status);

    List<Equipment> findByRenterId(Long renterId);

    boolean existsByEquipCode(String equipCode);

    /** 悲观写锁：租借 / 归还 / 报修与安全联锁的锁定、恢复互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁（按 id 升序）：停射事件批量锁定器材时用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id in :ids order by e.id")
    List<Equipment> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    /** 档案合并：把来源档案当前租出的器材改挂保留档案（仅改租用人，租出时间不动） */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Equipment e set e.renterId = :keepId, e.renterName = :keepName"
            + " where e.renterId = :sourceId and e.status = 'RENTED'")
    int reassignRenter(@Param("sourceId") Long sourceId,
            @Param("keepId") Long keepId,
            @Param("keepName") String keepName);
}
