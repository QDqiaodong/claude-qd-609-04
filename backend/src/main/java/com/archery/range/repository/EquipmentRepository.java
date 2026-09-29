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

    /** 某会员当前租出的器材（重复档案合并的预演与迁移使用） */
    List<Equipment> findByStatusAndRenterIdOrderByIdAsc(String status, Long renterId);

    boolean existsByEquipCode(String equipCode);

    /** 悲观写锁：租借 / 归还 / 报修与安全联锁的锁定、恢复互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);

    /** 悲观写锁（按 id 升序）：停射事件批量锁定器材时用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id in :ids order by e.id")
    List<Equipment> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    /** 悲观写锁：合并迁移来源会员当前租借器材时锁定，与租借 / 归还 / 安全联锁互斥 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.status = 'RENTED' and e.renterId = :renterId order by e.id")
    List<Equipment> findRentedByRenterForUpdate(@Param("renterId") Long renterId);

    /** 重复档案合并：来源会员当前租借器材改挂保留会员（租出时间原样保留） */
    @Modifying(flushAutomatically = true)
    @Query("update Equipment e set e.renterId = :keepId, e.renterName = :keepName"
            + " where e.status = 'RENTED' and e.renterId = :sourceId")
    int reassignRenter(@Param("sourceId") Long sourceId,
            @Param("keepId") Long keepId,
            @Param("keepName") String keepName);
}
