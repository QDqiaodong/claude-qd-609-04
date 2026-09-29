package com.archery.range.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.archery.range.domain.CertApplication;

import jakarta.persistence.LockModeType;

public interface CertApplicationRepository extends JpaRepository<CertApplication, Long> {

    /**
     * 悲观写锁：复核决定 / 补充证据 / 撤回先锁申请行，两个教练同时操作同一申请在此串行，
     * 后到者看到的已是对方落定的状态，只能得到冲突反馈。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CertApplication a where a.id = :id")
    Optional<CertApplication> findByIdForUpdate(@Param("id") Long id);

    List<CertApplication> findAllByOrderByIdDesc();

    List<CertApplication> findByMemberIdOrderByIdDesc(Long memberId);

    List<CertApplication> findByStatusOrderByIdDesc(String status);

    long countByStatus(String status);

    /** 当前有效认证：已通过且未过期（过期只是展示态，绝不进入有效集合） */
    List<CertApplication> findByMemberIdAndStatusAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
            Long memberId, String status, LocalDateTime now);

    /** 同一适用范围（会员 + 弓种 + 射距）已通过的认证，用于重新评定时取代旧认证 */
    List<CertApplication> findByMemberIdAndBowTypeAndDistanceAndStatus(
            Long memberId, String bowType, Integer distance, String status);

    /** 同一适用范围是否已有未办结申请：待复核 / 待补充证据 */
    @Query("select count(a) from CertApplication a where a.member.id = :memberId"
            + " and a.bowType = :bowType and a.distance = :distance and a.status in :statuses")
    long countOpenByScope(@Param("memberId") Long memberId,
            @Param("bowType") String bowType,
            @Param("distance") Integer distance,
            @Param("statuses") List<String> statuses);

    boolean existsByAppNo(String appNo);

    /**
     * 原子落最终结论：只有「待复核」的申请能被决定。
     * 配合申请行悲观锁与会员行锁，并发复核 / 重复点击只产生一个最终结论，返回 0 即冲突。
     */
    @Modifying(flushAutomatically = true)
    @Query("update CertApplication a set a.status = :status, a.reviewedBy = :reviewer,"
            + " a.reviewedAt = :reviewedAt, a.reviewNote = :note,"
            + " a.validFrom = :validFrom, a.validUntil = :validUntil"
            + " where a.id = :id and a.status = 'PENDING'")
    int decideIfPending(@Param("id") Long id,
            @Param("status") String status,
            @Param("reviewer") String reviewer,
            @Param("reviewedAt") LocalDateTime reviewedAt,
            @Param("note") String note,
            @Param("validFrom") LocalDateTime validFrom,
            @Param("validUntil") LocalDateTime validUntil);

    /** 原子撤回：只有仍有效的「已通过」认证能撤回 */
    @Modifying(flushAutomatically = true)
    @Query("update CertApplication a set a.status = 'WITHDRAWN', a.withdrawnBy = :operator,"
            + " a.withdrawnAt = :withdrawnAt, a.withdrawReason = :reason"
            + " where a.id = :id and a.status = 'APPROVED' and a.validUntil >= :now")
    int withdrawIfValid(@Param("id") Long id,
            @Param("operator") String operator,
            @Param("withdrawnAt") LocalDateTime withdrawnAt,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);

    /** 原子补充证据重新提交：只有「待补充证据」能回到待复核 */
    @Modifying(flushAutomatically = true)
    @Query("update CertApplication a set a.status = 'PENDING', a.windowStart = :windowStart,"
            + " a.windowEnd = :windowEnd, a.evalSeq = a.evalSeq + 1, a.reviewedBy = null,"
            + " a.reviewedAt = null, a.reviewNote = null"
            + " where a.id = :id and a.status = 'NEED_MORE'")
    int resubmitIfNeedMore(@Param("id") Long id,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd);
}
