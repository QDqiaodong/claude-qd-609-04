package com.archery.range.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.archery.range.domain.CertApplication;
import com.archery.range.domain.Course;
import com.archery.range.domain.CourseEnroll;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.domain.Tournament;
import com.archery.range.domain.TournamentTeam;
import com.archery.range.domain.TournamentTeamMember;
import com.archery.range.dto.MergeDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.CourseEnrollRepository;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MatchArrowRepository;
import com.archery.range.repository.RoundRepository;
import com.archery.range.repository.ShootoffArrowRepository;
import com.archery.range.repository.TournamentRepository;
import com.archery.range.repository.TournamentTeamMemberRepository;
import com.archery.range.repository.TournamentTeamRepository;

/**
 * 合并预演分析：读取两张卡在预演时刻的完整业务画像，判定互斥项与可自动处理项，
 * 计算余额 / 累计消费 / 等级的合并结果与业务数据指纹。纯只读、可重复执行，不落任何库。
 *
 * 指纹是确认合并的依据：预演之后任一会员的余额、报名、回合、租借或认证发生变化，
 * 重算指纹都会与预演版本不一致，确认必须拒绝并要求重新预演。
 */
final class MergeAnalysis {

    /** 未结束回合状态：进行中 / 暂停（安全联锁）都算 */
    private static final Set<String> OPEN_ROUND_STATUS = Set.of("ONGOING", "PAUSED");

    private final RoundRepository roundRepository;
    private final CourseEnrollRepository enrollRepository;
    private final CourseRepository courseRepository;
    private final EquipmentRepository equipmentRepository;
    private final com.archery.range.repository.EquipmentRentShadowRepository equipmentShadowRepository;
    private final LaneRepository laneRepository;
    private final CertApplicationRepository certApplicationRepository;
    private final TournamentTeamMemberRepository teamMemberRepository;
    private final TournamentTeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;
    private final MatchArrowRepository matchArrowRepository;
    private final ShootoffArrowRepository shootoffArrowRepository;

    MergeAnalysis(RoundRepository roundRepository,
            CourseEnrollRepository enrollRepository,
            CourseRepository courseRepository,
            EquipmentRepository equipmentRepository,
            com.archery.range.repository.EquipmentRentShadowRepository equipmentShadowRepository,
            LaneRepository laneRepository,
            CertApplicationRepository certApplicationRepository,
            TournamentTeamMemberRepository teamMemberRepository,
            TournamentTeamRepository teamRepository,
            TournamentRepository tournamentRepository,
            MatchArrowRepository matchArrowRepository,
            ShootoffArrowRepository shootoffArrowRepository) {
        this.roundRepository = roundRepository;
        this.enrollRepository = enrollRepository;
        this.courseRepository = courseRepository;
        this.equipmentRepository = equipmentRepository;
        this.equipmentShadowRepository = equipmentShadowRepository;
        this.laneRepository = laneRepository;
        this.certApplicationRepository = certApplicationRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
        this.matchArrowRepository = matchArrowRepository;
        this.shootoffArrowRepository = shootoffArrowRepository;
    }
    /** 一张卡的完整预演画像与指纹 */
    record Side(MergeDtos.MergeSide view, String fingerprint) {
    }

    /** 一次预演的全部分析结论 */
    record Report(
            Side keep,
            Side source,
            List<MergeDtos.ConflictItem> conflicts,
            List<MergeDtos.ResolveItem> resolves,
            MergeDtos.FinancePreview finance,
            MergeDtos.MigrationCounts migration) {

        boolean mergeable() {
            return conflicts.isEmpty();
        }
    }

    Report analyze(Member keepMember, Member sourceMember) {
        Side keep = buildSide(keepMember);
        Side source = buildSide(sourceMember);

        List<MergeDtos.ConflictItem> conflicts = new ArrayList<>();
        conflicts.addAll(ongoingRoundConflicts(keep, source));
        conflicts.addAll(sharedEquipmentConflicts(keep, source));
        conflicts.addAll(validCertConflicts(keep, source));
        conflicts.addAll(sameTournamentConflicts(keep, source));

        List<MergeDtos.ResolveItem> resolves = duplicateEnrollResolves(keep, source);

        MergeDtos.FinancePreview finance = finance(keepMember, sourceMember);
        MergeDtos.MigrationCounts migration = counts(keep, source, resolves);
        return new Report(keep, source, conflicts, resolves, finance, migration);
    }

    // ---------------- 单侧画像 ----------------

    private Side buildSide(Member member) {
        Long id = member.getId();
        List<Round> rounds = roundRepository.findByMemberIdOrderByStartTimeDesc(id);

        List<MergeDtos.RoundItem> ongoing = new ArrayList<>();
        List<MergeDtos.RoundItem> submitted = new ArrayList<>();
        int pbCount = 0;
        for (Round round : rounds) {
            MergeDtos.RoundItem item = toRoundItem(round);
            if (OPEN_ROUND_STATUS.contains(round.getStatus())) {
                ongoing.add(item);
            } else if ("SUBMITTED".equals(round.getStatus())) {
                submitted.add(item);
                if (Boolean.TRUE.equals(round.getPersonalBest())) {
                    pbCount += 1;
                }
            }
        }

        Map<Long, Course> courseMap = courseRepository.findAll().stream()
                .collect(Collectors.toMap(Course::getId, c -> c));
        List<MergeDtos.EnrollItem> enrollments = enrollRepository.findByMemberIdOrderByIdAsc(id).stream()
                .map(enroll -> toEnrollItem(enroll, courseMap.get(enroll.getCourse().getId())))
                .sorted(Comparator.comparing(MergeDtos.EnrollItem::enrollTime))
                .toList();

        List<MergeDtos.EquipmentItem> rented = equipmentRepository
                .findByStatusAndRenterIdOrderByIdAsc("RENTED", id).stream()
                .map(MergeAnalysis::toEquipmentItem)
                .collect(Collectors.toCollection(ArrayList::new));
        // 影子租约（merge_equipment_rent_shadow，仅用于异常数据排查 / 合并互斥检测）：
        // 物理器材只有一件，但历史误操作可能把同一件器材分别挂在两张卡名下，合并预演必须能识别交集。
        rented.addAll(equipmentShadowRepository.findByRenterIdOrderByEquipmentIdAsc(id).stream()
                .map(shadow -> new MergeDtos.EquipmentItem(
                        shadow.getEquipmentId(),
                        shadow.getEquipCode(),
                        RangeDict.equipTypeName(shadow.getType()),
                        shadow.getBrand(),
                        shadow.getRentedAt()))
                .toList());

        List<MergeDtos.LaneItem> lanes = laneRepository.findByStatusAndOccupantIdOrderByIdAsc("OCCUPIED", id).stream()
                .map(MergeAnalysis::toLaneItem)
                .toList();

        LocalDateTime now = LocalDateTime.now();
        List<CertApplication> apps = certApplicationRepository.findByMemberIdOrderByIdDesc(id);
        List<MergeDtos.CertItem> certApps = apps.stream()
                .map(app -> toCertItem(app, now))
                .toList();
        List<MergeDtos.CertItem> validCerts = apps.stream()
                .filter(app -> "APPROVED".equals(app.getStatus()))
                .filter(app -> app.getValidUntil() != null && !app.getValidUntil().isBefore(now))
                .map(app -> toCertItem(app, now))
                .toList();

        List<MergeDtos.TournamentItem> tournaments = tournamentItems(id);

        MergeDtos.MergeSide view = new MergeDtos.MergeSide(
                member.getId(),
                member.getCardNo(),
                member.getName(),
                member.getPhone(),
                member.getLevel(),
                RangeDict.memberLevelName(member.getLevel()),
                member.getBalance(),
                member.getTotalSpend(),
                member.getRegisterDate(),
                ongoing,
                new MergeDtos.RoundHistorySummary(submitted.size(), pbCount, submitted),
                enrollments,
                rented,
                lanes,
                certApps,
                validCerts,
                tournaments);
        return new Side(view, fingerprint(member, rounds, enrollments, rented, lanes, certApps, validCerts, tournaments));
    }

    // ---------------- 互斥项 ----------------

    /** 两张卡同时存在未结束（进行中 / 暂停）的计分回合：逐回合列出，必须先提交或撤销 */
    private List<MergeDtos.ConflictItem> ongoingRoundConflicts(Side keep, Side source) {
        List<MergeDtos.ConflictItem> list = new ArrayList<>();
        if (!keep.view().ongoingRounds().isEmpty() && !source.view().ongoingRounds().isEmpty()) {
            String detail = "保留档案有 " + keep.view().ongoingRounds().size()
                    + " 个未结束计分回合，来源档案有 " + source.view().ongoingRounds().size()
                    + " 个；请先在两侧分别提交 / 撤销完成后再合并，系统不会自行挑选回合";
            list.add(new MergeDtos.ConflictItem("ONGOING_ROUND", "双方都有进行中的计分回合",
                    detail,
                    keep.view().ongoingRounds().get(0).id(),
                    source.view().ongoingRounds().get(0).id(),
                    keep.view().ongoingRounds().stream().map(MergeDtos.RoundItem::roundNo)
                            .collect(Collectors.joining("、")),
                    source.view().ongoingRounds().stream().map(MergeDtos.RoundItem::roundNo)
                            .collect(Collectors.joining("、"))));
        }
        return list;
    }

    /** 两张卡租着同一件器材：逐件列出，必须先归还一方 */
    private List<MergeDtos.ConflictItem> sharedEquipmentConflicts(Side keep, Side source) {
        List<MergeDtos.ConflictItem> list = new ArrayList<>();
        Map<Long, MergeDtos.EquipmentItem> keepMap = keep.view().rentedEquipment().stream()
                .collect(Collectors.toMap(MergeDtos.EquipmentItem::equipmentId, item -> item));
        for (MergeDtos.EquipmentItem sourceItem : source.view().rentedEquipment()) {
            MergeDtos.EquipmentItem keepItem = keepMap.get(sourceItem.equipmentId());
            if (keepItem != null) {
                list.add(new MergeDtos.ConflictItem("RENTED_SAME_EQUIPMENT", "双方租出了同一件器材",
                        "器材 " + sourceItem.equipCode() + "（" + sourceItem.typeName() + " · "
                                + sourceItem.brand() + "）同时挂在两张卡名下，物理上不可能同时归属，"
                                + "请先归还其中一方再合并",
                        keepItem.equipmentId(), sourceItem.equipmentId(),
                        keepItem.equipCode(), sourceItem.equipCode()));
            }
        }
        return list;
    }

    /**
     * 同一弓种两侧都持有有效认证即互斥：弓种认证的适用范围为「该弓种认证射距及以内」，
     * 同弓种的两条有效认证适用范围必然重叠，不能自行挑一条覆盖。
     */
    private List<MergeDtos.ConflictItem> validCertConflicts(Side keep, Side source) {
        List<MergeDtos.ConflictItem> list = new ArrayList<>();
        Map<String, MergeDtos.CertItem> keepByBow = keep.view().validCerts().stream()
                .collect(Collectors.toMap(MergeDtos.CertItem::bowType, item -> item, (a, b) -> a));
        Set<String> done = new HashSet<>();
        for (MergeDtos.CertItem sourceCert : source.view().validCerts()) {
            MergeDtos.CertItem keepCert = keepByBow.get(sourceCert.bowType());
            if (keepCert != null && done.add(sourceCert.bowType())) {
                list.add(new MergeDtos.ConflictItem("VALID_CERT_SCOPE", "双方持有同一弓种适用范围重叠的有效认证",
                        "两张卡都持有有效的「" + sourceCert.bowTypeName() + "」认证（保留卡 "
                                + keepCert.appNo() + " 覆盖 " + keepCert.distance() + " 米及以内；来源卡 "
                                + sourceCert.appNo() + " 覆盖 " + sourceCert.distance()
                                + " 米及以内），适用范围重叠，请先撤回 / 等待其中一条失效后再合并，"
                                + "系统不会自行挑一条覆盖",
                        keepCert.applicationId(), sourceCert.applicationId(),
                        keepCert.appNo() + "（" + keepCert.distance() + "米）",
                        sourceCert.appNo() + "（" + sourceCert.distance() + "米）"));
            }
        }
        return list;
    }

    /** 两张卡在同一赛事中各占一个队员名额（哪怕不同队）：迁移会违反 (赛事, 会员) 唯一约束 */
    private List<MergeDtos.ConflictItem> sameTournamentConflicts(Side keep, Side source) {
        List<MergeDtos.ConflictItem> list = new ArrayList<>();
        Map<Long, MergeDtos.TournamentItem> keepByTournament = keep.view().tournamentEntries().stream()
                .collect(Collectors.toMap(MergeDtos.TournamentItem::tournamentId, item -> item, (a, b) -> a));
        for (MergeDtos.TournamentItem sourceItem : source.view().tournamentEntries()) {
            MergeDtos.TournamentItem keepItem = keepByTournament.get(sourceItem.tournamentId());
            if (keepItem != null) {
                list.add(new MergeDtos.ConflictItem("SAME_TOURNAMENT", "双方在同一场赛事中各占队员名额",
                        "赛事「" + sourceItem.tournamentName() + "」中两张卡各是一名队员（保留卡在 "
                                + keepItem.teamName() + "，来源卡在 " + sourceItem.teamName()
                                + "），合并后会同人占两个名额，请先在赛事中调整队员后再合并",
                        null, null,
                        keepItem.teamName(), sourceItem.teamName()));
            }
        }
        return list;
    }

    // ---------------- 可自动处理项：同课程重复报名去重（保留较早一条） ----------------

    private List<MergeDtos.ResolveItem> duplicateEnrollResolves(Side keep, Side source) {
        List<MergeDtos.ResolveItem> list = new ArrayList<>();
        Map<Long, MergeDtos.EnrollItem> keepByCourse = keep.view().enrollments().stream()
                .collect(Collectors.toMap(MergeDtos.EnrollItem::courseId, item -> item, (a, b) -> a));
        for (MergeDtos.EnrollItem sourceItem : source.view().enrollments()) {
            MergeDtos.EnrollItem keepItem = keepByCourse.get(sourceItem.courseId());
            if (keepItem == null) {
                continue;
            }
            boolean sourceEarlier = sourceItem.enrollTime().isBefore(keepItem.enrollTime());
            MergeDtos.EnrollItem kept = sourceEarlier ? sourceItem : keepItem;
            MergeDtos.EnrollItem dropped = sourceEarlier ? keepItem : sourceItem;
            list.add(new MergeDtos.ResolveItem(
                    "DEDUP_ENROLL_KEEP_EARLIEST",
                    "同一课程重复报名：保留报名较早的一条",
                    "课程「" + sourceItem.courseName() + "」两张卡均已报名，保留 "
                            + kept.enrollTime() + " 的报名记录（"
                            + (kept == sourceItem ? "来源卡" : "保留卡") + "），删除较晚的一条",
                    kept.enrollId(),
                    kept == sourceItem ? "SOURCE" : "KEEP",
                    dropped.enrollId(),
                    dropped == sourceItem ? "SOURCE" : "KEEP",
                    kept.enrollTime(),
                    dropped.enrollTime()));
        }
        return list;
    }

    // ---------------- 金额 / 等级 / 迁移量 ----------------

    private MergeDtos.FinancePreview finance(Member keep, Member source) {
        BigDecimal mergedBalance = keep.getBalance().add(source.getBalance());
        BigDecimal mergedSpend = keep.getTotalSpend().add(source.getTotalSpend());
        // 等级按合并后的累计消费重新计算；同时不得低于两张卡原等级中的较高者（只升不降）
        String recalculated = RangeDict.levelByTotalSpend(mergedSpend);
        int rank = Math.max(RangeDict.memberLevelRank(recalculated),
                Math.max(RangeDict.memberLevelRank(keep.getLevel()), RangeDict.memberLevelRank(source.getLevel())));
        String finalLevel = byRank(rank, recalculated);
        boolean keptFromHigher = RangeDict.memberLevelRank(finalLevel) > RangeDict.memberLevelRank(recalculated);
        return new MergeDtos.FinancePreview(
                keep.getBalance(), source.getBalance(), mergedBalance,
                keep.getTotalSpend(), source.getTotalSpend(), mergedSpend,
                keep.getLevel(), source.getLevel(), recalculated, finalLevel,
                RangeDict.memberLevelName(finalLevel), keptFromHigher);
    }

    private static String byRank(int rank, String fallback) {
        for (String level : List.of("NORMAL", "SILVER", "GOLD")) {
            if (RangeDict.memberLevelRank(level) == rank) {
                return level;
            }
        }
        return fallback;
    }

    private MergeDtos.MigrationCounts counts(Side keep, Side source, List<MergeDtos.ResolveItem> resolves) {
        // 报名迁移数 = 来源报名总数 - 重复去重数（重复课程只保留较早一条，晚的一条删除）
        int sourceEnrollCount = source.view().enrollments().size();
        int enrollDropped = resolves.size();
        int enrollMoved = sourceEnrollCount - enrollDropped;
        // 回合总数（已完成 + 未结束）；互斥存在时预演不可确认，数字仅用于展示
        int rounds = source.view().roundHistory().submittedCount() + source.view().ongoingRounds().size();
        int tournament = source.view().tournamentEntries().size()
                + (int) matchArrowRepository.countByMemberId(source.view().memberId())
                + (int) shootoffArrowRepository.countByMemberId(source.view().memberId());
        return new MergeDtos.MigrationCounts(
                rounds,
                enrollMoved,
                enrollDropped,
                source.view().rentedEquipment().size(),
                source.view().occupiedLanes().size(),
                source.view().certApplications().size(),
                tournament);
    }

    private List<MergeDtos.TournamentItem> tournamentItems(Long memberId) {
        List<TournamentTeamMember> binds = teamMemberRepository.findByMemberIdOrderByTournamentIdAsc(memberId);
        if (binds.isEmpty()) {
            return List.of();
        }
        Map<Long, Tournament> tournamentMap = tournamentRepository.findAllById(binds.stream()
                .map(TournamentTeamMember::getTournamentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Tournament::getId, t -> t));
        Map<Long, TournamentTeam> teamMap = teamRepository.findAllById(binds.stream()
                .map(TournamentTeamMember::getTeamId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(TournamentTeam::getId, t -> t));
        long matchArrows = matchArrowRepository.countByMemberId(memberId);
        long shootoffArrows = shootoffArrowRepository.countByMemberId(memberId);
        return binds.stream()
                .map(bind -> {
                    Tournament tournament = tournamentMap.get(bind.getTournamentId());
                    TournamentTeam team = teamMap.get(bind.getTeamId());
                    return new MergeDtos.TournamentItem(
                            bind.getTournamentId(),
                            tournament == null ? "赛事#" + bind.getTournamentId() : tournament.getName(),
                            tournament == null ? "" : tournament.getStatus(),
                            bind.getTeamId(),
                            team == null ? "队伍#" + bind.getTeamId() : team.getTeamName(),
                            bind.getPosition(),
                            (int) (matchArrows + shootoffArrows));
                })
                .toList();
    }

    // ---------------- 指纹 ----------------

    /**
     * 业务数据指纹：覆盖余额、累计消费、等级与题目要求的全部业务关系。
     * 规范化文本排序后做 SHA-256，任一字段变化指纹即变。
     */
    private String fingerprint(Member member,
            List<Round> rounds,
            List<MergeDtos.EnrollItem> enrollments,
            List<MergeDtos.EquipmentItem> rented,
            List<MergeDtos.LaneItem> lanes,
            List<MergeDtos.CertItem> certApps,
            List<MergeDtos.CertItem> validCerts,
            List<MergeDtos.TournamentItem> tournaments) {
        List<String> lines = new ArrayList<>();
        lines.add("MEMBER#" + member.getId()
                + "|status=" + safe(member.getStatus())
                + "|level=" + safe(member.getLevel())
                + "|balance=" + member.getBalance().toPlainString()
                + "|spend=" + member.getTotalSpend().toPlainString());
        for (Round round : rounds) {
            lines.add("ROUND#" + round.getId()
                    + "|status=" + safe(round.getStatus())
                    + "|pb=" + Boolean.TRUE.equals(round.getPersonalBest())
                    + "|score=" + round.getTotalScore()
                    + "|completed=" + (round.getCompletedAt() == null ? "" : round.getCompletedAt())
                    + "|arrows=" + round.getArrows().size());
        }
        for (MergeDtos.EnrollItem item : enrollments) {
            lines.add("ENROLL#" + item.enrollId() + "|course=" + item.courseId() + "|at=" + item.enrollTime());
        }
        for (MergeDtos.EquipmentItem item : rented) {
            lines.add("EQUIP#" + item.equipmentId() + "|rentedAt=" + item.rentedAt());
        }
        for (MergeDtos.LaneItem item : lanes) {
            lines.add("LANE#" + item.laneId() + "|openedAt=" + item.openedAt());
        }
        for (MergeDtos.CertItem item : certApps) {
            lines.add("CERTAPP#" + item.applicationId() + "|status=" + item.status()
                    + "|validUntil=" + (item.validUntil() == null ? "" : item.validUntil()));
        }
        for (MergeDtos.CertItem item : validCerts) {
            lines.add("VALIDCERT#" + item.applicationId());
        }
        for (MergeDtos.TournamentItem item : tournaments) {
            lines.add("TOURN#" + item.tournamentId() + "|team=" + item.teamId() + "|pos=" + item.position());
        }
        lines.sort(Comparator.naturalOrder());
        return sha256(String.join("\n", lines));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算合并指纹", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ---------------- DTO 组装 ----------------

    private MergeDtos.RoundItem toRoundItem(Round round) {
        return new MergeDtos.RoundItem(
                round.getId(),
                round.getRoundNo(),
                round.getStatus(),
                RangeDict.roundStatusName(round.getStatus()),
                round.getBowType(),
                RangeDict.bowTypeName(round.getBowType()),
                round.getLane() == null ? null : round.getLane().getDistance(),
                round.getLane() == null ? "" : round.getLane().getLaneNo(),
                round.getArrowCount(),
                round.getStartTime());
    }

    private MergeDtos.EnrollItem toEnrollItem(CourseEnroll enroll, Course course) {
        return new MergeDtos.EnrollItem(
                enroll.getId(),
                course == null ? enroll.getCourse().getId() : course.getId(),
                course == null ? "课程#" + enroll.getCourse().getId() : course.getCourseName(),
                course == null ? null : course.getClassTime(),
                enroll.getEnrollTime());
    }

    private static MergeDtos.EquipmentItem toEquipmentItem(Equipment equipment) {
        return new MergeDtos.EquipmentItem(
                equipment.getId(),
                equipment.getEquipCode(),
                RangeDict.equipTypeName(equipment.getType()),
                equipment.getBrand(),
                equipment.getRentedAt());
    }

    private static MergeDtos.LaneItem toLaneItem(Lane lane) {
        return new MergeDtos.LaneItem(
                lane.getId(),
                lane.getLaneNo(),
                lane.getDistance(),
                lane.getOpenedAt(),
                lane.getPlannedHours());
    }

    private MergeDtos.CertItem toCertItem(CertApplication app, LocalDateTime now) {
        boolean expired = app.getValidUntil() != null && app.getValidUntil().isBefore(now);
        String display = "APPROVED".equals(app.getStatus()) && expired ? "EXPIRED" : app.getStatus();
        return new MergeDtos.CertItem(
                app.getId(),
                app.getAppNo(),
                app.getBowType(),
                RangeDict.bowTypeName(app.getBowType()),
                app.getDistance(),
                app.getStatus(),
                RangeDict.certStatusName(app.getStatus()),
                display,
                RangeDict.certStatusName(display),
                app.getValidUntil());
    }
}
