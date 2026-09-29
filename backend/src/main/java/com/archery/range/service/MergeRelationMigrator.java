package com.archery.range.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.dto.MergeDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.CourseEnrollRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MatchArrowRepository;
import com.archery.range.repository.RoundRepository;
import com.archery.range.repository.ShootoffArrowRepository;
import com.archery.range.repository.TournamentTeamMemberRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 合并业务关系迁移器（合并事务内调用，独立成 Bean 便于故障注入验证整体回滚）。
 *
 * 全部为批量 UPDATE，只把外键 / 挂名从来源档案改到保留档案，绝不改写任何业务原始内容：
 * 箭值 / 总分 / 完成时间、认证规则快照与有效期、器材租出时间、箭道开台时间均原样保留。
 *
 * 每一步之间 {@link EntityManager#flush()} + {@link EntityManager#clear()}：
 * 及时把约束冲突（理论上已被预演互斥拦截）抛在当步，并保证下一步读到的是迁移后的最新库状态，
 * 任一步抛异常都会由外层合并事务整体回滚，来源档案仍保持可用。
 */
@Service
public class MergeRelationMigrator {

    private final RoundRepository roundRepository;
    private final CourseEnrollRepository enrollRepository;
    private final EquipmentRepository equipmentRepository;
    private final LaneRepository laneRepository;
    private final CertApplicationRepository certApplicationRepository;
    private final TournamentTeamMemberRepository teamMemberRepository;
    private final MatchArrowRepository matchArrowRepository;
    private final ShootoffArrowRepository shootoffArrowRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** 测试用故障注入：迁移到指定步骤前抛异常，验证任一步失败整体回滚（正常业务为 null） */
    private volatile String failBeforeStep;

    public MergeRelationMigrator(RoundRepository roundRepository,
            CourseEnrollRepository enrollRepository,
            EquipmentRepository equipmentRepository,
            LaneRepository laneRepository,
            CertApplicationRepository certApplicationRepository,
            TournamentTeamMemberRepository teamMemberRepository,
            MatchArrowRepository matchArrowRepository,
            ShootoffArrowRepository shootoffArrowRepository) {
        this.roundRepository = roundRepository;
        this.enrollRepository = enrollRepository;
        this.certApplicationRepository = certApplicationRepository;
        this.equipmentRepository = equipmentRepository;
        this.laneRepository = laneRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.matchArrowRepository = matchArrowRepository;
        this.shootoffArrowRepository = shootoffArrowRepository;
    }

    /** 迁移步骤名（故障注入 / 轨迹明细用） */
    public static final String STEP_DROP_DUP_ENROLL = "DROP_DUP_ENROLL";
    public static final String STEP_COURSE_ENROLL = "COURSE_ENROLL";
    public static final String STEP_ROUND = "ROUND";
    public static final String STEP_EQUIPMENT = "EQUIPMENT";
    public static final String STEP_LANE = "LANE";
    public static final String STEP_CERT = "CERT";
    public static final String STEP_TOURNAMENT = "TOURNAMENT";

    /** 各类关系的迁移结果（数量与逐项明细） */
    public record MigrationResult(
            int roundsMoved,
            int enrollmentsMoved,
            int enrollmentsDropped,
            int equipmentMoved,
            int lanesMoved,
            int certsMoved,
            int tournamentMoved,
            List<MergeDtos.TrailDetailItem> details) {
    }

    /**
     * 执行全部业务关系迁移。
     *
     * @param keepId               保留会员 id
     * @param keepName             保留会员姓名（器材 / 箭道挂名用）
     * @param sourceId             来源会员 id
     * @param duplicateEnrollIds   同一课程重复报名中需要删除的报名行 id（保留较早一条）
     */
    @Transactional
    public MigrationResult migrate(Long keepId, String keepName, Long sourceId, List<Long> duplicateEnrollIds) {
        List<MergeDtos.TrailDetailItem> details = new ArrayList<>();

        // 1) 先删除重复报名的较晚行，消除 uk_enroll(course_id, member_id) 冲突，再迁移剩余报名
        failIfNeeded(STEP_DROP_DUP_ENROLL);
        int dropped = 0;
        if (!duplicateEnrollIds.isEmpty()) {
            List<Long> safeIds = new ArrayList<>(duplicateEnrollIds);
            enrollRepository.findAllById(safeIds).forEach(enrollRepository::delete);
            enrollRepository.flush();
            dropped = safeIds.size();
            details.add(new MergeDtos.TrailDetailItem("ENROLL_DEDUP", "重复课程报名去重",
                    "DELETE_LATE", "删除同一课程重复报名中报名较晚的 " + dropped + " 条，保留较早一条"));
        }
        entityManager.clear();

        failIfNeeded(STEP_COURSE_ENROLL);
        int enrollMoved = enrollRepository.reassignMember(sourceId, keepId);
        if (enrollMoved > 0) {
            details.add(new MergeDtos.TrailDetailItem("ENROLL", "课程报名",
                    "REASSIGN", "迁移课程报名 " + enrollMoved + " 条到保留档案（报名时间原样保留）"));
        }
        entityManager.clear();

        // 2) 全部计分回合（含已完成历史与未结束回合）迁移；箭值 / 完成时间 / 弓种不动
        failIfNeeded(STEP_ROUND);
        int roundsMoved = roundRepository.reassignMember(sourceId, keepId);
        if (roundsMoved > 0) {
            details.add(new MergeDtos.TrailDetailItem("ROUND", "计分回合",
                    "REASSIGN", "迁移计分回合 " + roundsMoved
                            + " 个（含历史成绩；箭值、总分、完成时间原样保留，稍后按保留会员重算个人最好分组）"));
        }
        entityManager.clear();

        // 3) 当前租借器材改挂保留会员（锁行已由外层合并事务完成），租出时间不动
        failIfNeeded(STEP_EQUIPMENT);
        int equipmentMoved = equipmentRepository.reassignRenter(sourceId, keepId, keepName);
        if (equipmentMoved > 0) {
            details.add(new MergeDtos.TrailDetailItem("EQUIPMENT", "当前器材租借",
                    "REASSIGN", "迁移当前租借器材 " + equipmentMoved + " 件（租出时间原样保留）"));
        }
        entityManager.clear();

        // 4) 当前占用箭道改挂保留会员，开台时间 / 预计时长不动
        failIfNeeded(STEP_LANE);
        int lanesMoved = laneRepository.reassignOccupant(sourceId, keepId, keepName);
        if (lanesMoved > 0) {
            details.add(new MergeDtos.TrailDetailItem("LANE", "占用箭道",
                    "REASSIGN", "迁移占用中的箭道 " + lanesMoved + " 条（开台时间与预计时长原样保留）"));
        }
        entityManager.clear();

        // 5) 认证申请 / 有效认证 / 历史全部迁移；状态、规则快照、有效期、评定与日志不动
        failIfNeeded(STEP_CERT);
        int certsMoved = certApplicationRepository.reassignMember(sourceId, keepId);
        if (certsMoved > 0) {
            details.add(new MergeDtos.TrailDetailItem("CERT", "认证申请与有效认证",
                    "REASSIGN", "迁移认证申请 / 有效认证 " + certsMoved
                            + " 笔（认证状态、规则快照、有效期与评定历史原样保留）"));
        }
        entityManager.clear();

        // 6) 赛事队员身份与赛事箭值（局内 / 加赛）迁移；同赛事各占名额的互斥已在预演拦截
        failIfNeeded(STEP_TOURNAMENT);
        int teamBinds = teamMemberRepository.reassignMember(sourceId, keepId);
        int matchArrows = matchArrowRepository.reassignMember(sourceId, keepId);
        int shootoffArrows = shootoffArrowRepository.reassignMember(sourceId, keepId);
        int tournamentMoved = teamBinds + matchArrows + shootoffArrows;
        if (tournamentMoved > 0) {
            details.add(new MergeDtos.TrailDetailItem("TOURNAMENT", "赛事参与",
                    "REASSIGN", "迁移赛事队员身份 " + teamBinds + " 个、局内箭值 " + matchArrows
                            + " 支、加赛箭值 " + shootoffArrows + " 支（站位与箭值原样保留）"));
        }
        entityManager.clear();

        return new MigrationResult(roundsMoved, enrollMoved, dropped,
                equipmentMoved, lanesMoved, certsMoved, tournamentMoved, details);
    }

    /** 仅供集成测试：在下一次执行到指定步骤前注入故障，验证整体回滚 */
    public void injectFailureBeforeStep(String step) {
        this.failBeforeStep = step;
    }

    /** 仅供集成测试：清除故障注入 */
    public void clearInjectedFailure() {
        this.failBeforeStep = null;
    }

    private void failIfNeeded(String step) {
        if (step.equals(failBeforeStep)) {
            throw new IllegalStateException("注入的迁移中断故障，发生在步骤 " + step);
        }
    }
}
