package com.archery.range.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Lane;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;
import com.archery.range.domain.SafetyEvent;
import com.archery.range.domain.SafetyEventEquipment;
import com.archery.range.domain.SafetyEventLane;
import com.archery.range.domain.SafetyEventLog;
import com.archery.range.domain.SafetyEventRound;
import com.archery.range.domain.SafetyReview;
import com.archery.range.dto.SafetyDtos;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.RoundRepository;
import com.archery.range.repository.SafetyEventEquipmentRepository;
import com.archery.range.repository.SafetyEventLaneRepository;
import com.archery.range.repository.SafetyEventLogRepository;
import com.archery.range.repository.SafetyEventRepository;
import com.archery.range.repository.SafetyEventRoundRepository;
import com.archery.range.repository.SafetyReviewRepository;

/**
 * 安全停射联锁台。
 *
 * 关键设计：
 * 1. 联锁不覆盖原状态 —— 箭道 / 器材 / 回合被锁前先把业务状态快照进
 *    safety_event_lane / safety_event_equipment / safety_event_round（prev_status），
 *    锁定期间只把状态列置为 LOCKED / PAUSED，占用人、租借人、已记箭支一律不动；
 *    放行时按快照逐一恢复。
 * 2. 双人复核 —— 教练（RANGE）与器材管理员（EQUIPMENT）各形成一份结论，
 *    创建人不能签字；任一不通过退回待处置并继续锁定，结论随轮次保留。
 * 3. 并发一致 —— 事件行悲观锁串行化复核与放行；复核结论用「仅待复核可写」的原子更新，
 *    同一复核项只有一份最终结论；放行在单一事务内完成，要么全部恢复要么整体回滚，
 *    失败后事件保持未放行，可安全重试。
 */
@Service
public class SafetyService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SafetyEventRepository eventRepository;
    private final SafetyEventLaneRepository laneItemRepository;
    private final SafetyEventEquipmentRepository equipItemRepository;
    private final SafetyEventRoundRepository roundItemRepository;
    private final SafetyReviewRepository reviewRepository;
    private final SafetyEventLogRepository logRepository;
    private final LaneRepository laneRepository;
    private final EquipmentRepository equipmentRepository;
    private final RoundRepository roundRepository;

    public SafetyService(SafetyEventRepository eventRepository,
            SafetyEventLaneRepository laneItemRepository,
            SafetyEventEquipmentRepository equipItemRepository,
            SafetyEventRoundRepository roundItemRepository,
            SafetyReviewRepository reviewRepository,
            SafetyEventLogRepository logRepository,
            LaneRepository laneRepository,
            EquipmentRepository equipmentRepository,
            RoundRepository roundRepository) {
        this.eventRepository = eventRepository;
        this.laneItemRepository = laneItemRepository;
        this.equipItemRepository = equipItemRepository;
        this.roundItemRepository = roundItemRepository;
        this.reviewRepository = reviewRepository;
        this.logRepository = logRepository;
        this.laneRepository = laneRepository;
        this.equipmentRepository = equipmentRepository;
        this.roundRepository = roundRepository;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<SafetyDtos.EventSummary> list(boolean activeOnly) {
        List<SafetyEvent> events = activeOnly
                ? eventRepository.findByStatusNotOrderByIdDesc("RELEASED")
                : eventRepository.findAllByOrderByIdDesc();
        return events.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public SafetyDtos.EventView detail(Long id) {
        SafetyEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new BizException("停射事件不存在：id=" + id));
        return toView(event);
    }

    // ---------------- 建立事件（立即联锁） ----------------

    @Transactional
    public SafetyDtos.EventView create(SafetyDtos.CreateReq req) {
        String operator = requireName(req.operator(), "值班经理");
        if (!RangeDict.isValidSafetyReason(req.reason())) {
            throw new BizException("停射原因只能是：箭道设备异常 / 人员闯入射线 / 疑似器材故障 / 其他");
        }
        if (!RangeDict.isValidSafetySeverity(req.severity())) {
            throw new BizException("严重级别只能是：一般 / 严重 / 紧急");
        }
        String description = req.description() == null ? "" : req.description().trim();
        if (description.isEmpty()) {
            throw new BizException("请填写现场说明");
        }

        List<Long> laneIds = new ArrayList<>(new LinkedHashSet<>(req.laneIds() == null ? List.of() : req.laneIds()));
        List<Long> equipIds = new ArrayList<>(new LinkedHashSet<>(req.equipmentIds() == null ? List.of() : req.equipmentIds()));
        if (laneIds.isEmpty() && equipIds.isEmpty()) {
            throw new BizException("请至少选择一条受影响箭道或一件受影响器材");
        }

        // 悲观锁按 id 升序批量锁定资源，与开台 / 记箭 / 租借等写操作互斥
        List<Lane> lanes = laneIds.isEmpty() ? List.of() : laneRepository.findAllByIdForUpdate(laneIds);
        if (lanes.size() != laneIds.size()) {
            throw new BizException("所选箭道不存在，请刷新后重试");
        }
        for (Lane lane : lanes) {
            if ("LOCKED".equals(lane.getStatus())) {
                throw new BizException("箭道 [" + lane.getLaneNo() + "] 已处于安全锁定，请并入原有停射事件处理");
            }
        }
        List<Equipment> equips = equipIds.isEmpty() ? List.of() : equipmentRepository.findAllByIdForUpdate(equipIds);
        if (equips.size() != equipIds.size()) {
            throw new BizException("所选器材不存在，请刷新后重试");
        }
        for (Equipment equip : equips) {
            if ("LOCKED".equals(equip.getStatus())) {
                throw new BizException("器材 [" + equip.getEquipCode() + "] 已处于安全锁定，请并入原有停射事件处理");
            }
        }
        List<Round> rounds = laneIds.isEmpty() ? List.of() : roundRepository.findOngoingByLaneIdsForUpdate(laneIds);

        SafetyEvent event = new SafetyEvent();
        event.setEventNo(nextEventNo());
        event.setReason(req.reason());
        event.setSeverity(req.severity());
        event.setDescription(description);
        event.setStatus("PENDING");
        event.setReviewRound(1);
        event.setCreatedBy(operator);
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);

        for (Lane lane : lanes) {
            SafetyEventLane item = new SafetyEventLane();
            item.setEventId(event.getId());
            item.setLaneId(lane.getId());
            item.setPrevStatus(lane.getStatus());
            item.setRestored(false);
            laneItemRepository.save(item);
            lane.setStatus("LOCKED");
            laneRepository.save(lane);
        }
        for (Round round : rounds) {
            SafetyEventRound item = new SafetyEventRound();
            item.setEventId(event.getId());
            item.setRoundId(round.getId());
            item.setPrevStatus(round.getStatus());
            item.setRestored(false);
            roundItemRepository.save(item);
            round.setStatus("PAUSED");
            roundRepository.save(round);
        }
        for (Equipment equip : equips) {
            SafetyEventEquipment item = new SafetyEventEquipment();
            item.setEventId(event.getId());
            item.setEquipmentId(equip.getId());
            item.setPrevStatus(equip.getStatus());
            item.setRestored(false);
            equipItemRepository.save(item);
            equip.setStatus("LOCKED");
            equipmentRepository.save(equip);
        }

        log(event.getId(), "CREATE", operator, "MANAGER",
                RangeDict.safetyReasonName(event.getReason()) + " · " + RangeDict.safetySeverityName(event.getSeverity())
                        + "；锁定箭道 " + lanes.size() + " 条、器材 " + equips.size() + " 件、暂停回合 " + rounds.size() + " 个");
        return toView(event);
    }

    // ---------------- 发起分项复核：待处置 → 分项复核 ----------------

    @Transactional
    public SafetyDtos.EventView startReview(Long id, SafetyDtos.OperatorReq req) {
        SafetyEvent event = lockEvent(id);
        String operator = requireName(req.operator(), "值班经理");
        if (!"PENDING".equals(event.getStatus())) {
            throw new BizException("事件当前为「" + RangeDict.safetyStatusName(event.getStatus()) + "」，不能发起复核");
        }
        // 复核不通过退回后再次发起：轮次 +1，历史结论保留
        if (!reviewRepository.findByEventIdAndReviewRound(id, event.getReviewRound()).isEmpty()) {
            event.setReviewRound(event.getReviewRound() + 1);
        }
        for (String item : List.of("RANGE", "EQUIPMENT")) {
            SafetyReview review = new SafetyReview();
            review.setEventId(id);
            review.setReviewRound(event.getReviewRound());
            review.setItem(item);
            reviewRepository.save(review);
        }
        event.setStatus("REVIEWING");
        eventRepository.save(event);
        log(id, "START_REVIEW", operator, "MANAGER", "发起第 " + event.getReviewRound() + " 轮分项复核");
        return toView(event);
    }

    // ---------------- 分项复核：教练 / 器材管理员各自签字 ----------------

    @Transactional
    public SafetyDtos.EventView review(Long id, SafetyDtos.ReviewReq req) {
        SafetyEvent event = lockEvent(id);
        if (!"REVIEWING".equals(event.getStatus())) {
            throw new BizException("事件当前为「" + RangeDict.safetyStatusName(event.getStatus()) + "」，不在分项复核阶段");
        }
        if (!RangeDict.isValidReviewItem(req.item())) {
            throw new BizException("复核项只能是 RANGE（射线与人员安全）或 EQUIPMENT（器材检查）");
        }
        if (!RangeDict.isValidConclusion(req.conclusion())) {
            throw new BizException("复核结论只能是通过或不通过");
        }
        String reviewer = requireName(req.operator(), "复核人");
        if (reviewer.equals(event.getCreatedBy())) {
            throw new BizException("事件创建人不能代替复核角色签字，请由"
                    + ("RANGE".equals(req.item()) ? "教练" : "器材管理员") + "本人复核");
        }
        String note = req.note() == null ? "" : req.note().trim();
        if ("FAIL".equals(req.conclusion()) && note.isEmpty()) {
            throw new BizException("复核不通过时必须填写原因");
        }

        List<SafetyReview> items = reviewRepository.findByEventIdAndReviewRound(id, event.getReviewRound());
        SafetyReview target = items.stream()
                .filter(item -> item.getItem().equals(req.item()))
                .findFirst()
                .orElseThrow(() -> new BizException("复核项不存在，请刷新后重试"));
        // 双人复核：同一轮两项结论不能由同一人签字
        items.stream()
                .filter(item -> !item.getItem().equals(req.item()) && item.getConclusion() != null)
                .findFirst()
                .ifPresent(other -> {
                    if (reviewer.equals(other.getReviewer())) {
                        throw new BizException("两项复核需由不同人员分别签字，「"
                                + RangeDict.reviewItemName(other.getItem()) + "」已由 " + other.getReviewer() + " 复核");
                    }
                });

        // 原子落结论：并发复核 / 重复点击，同一复核项只形成一份最终结论
        LocalDateTime now = LocalDateTime.now();
        int updated = reviewRepository.concludeIfPending(target.getId(), req.conclusion(), reviewer, now, note);
        if (updated == 0) {
            throw new BizException("「" + RangeDict.reviewItemName(req.item()) + "」已有复核结论，请勿重复提交");
        }
        // 批量更新绕过持久化上下文，这里同步内存中的实体，保证后续判断与返回视图一致
        target.setConclusion(req.conclusion());
        target.setReviewer(reviewer);
        target.setReviewedAt(now);
        target.setNote(note);

        String role = "RANGE".equals(req.item()) ? "COACH" : "KEEPER";
        String itemName = RangeDict.reviewItemName(req.item());
        if ("FAIL".equals(req.conclusion())) {
            log(id, "REVIEW_FAIL", reviewer, role, itemName + " 复核不通过：" + note);
            event.setStatus("PENDING");
            eventRepository.save(event);
            log(id, "BACK_PENDING", "联锁系统", "SYSTEM", "因「" + itemName + "」复核不通过，事件退回待处置，资源继续锁定");
        } else {
            log(id, "REVIEW_PASS", reviewer, role, itemName + " 复核通过" + (note.isEmpty() ? "" : "：" + note));
            boolean allPass = items.stream().allMatch(item -> "PASS".equals(item.getConclusion()));
            if (allPass) {
                event.setStatus("CLEARED");
                eventRepository.save(event);
                log(id, "CLEARED", "联锁系统", "SYSTEM", "教练与器材管理员复核均通过，允许值班经理执行复射放行");
            }
        }
        return toView(event);
    }

    // ---------------- 复射放行：单一事务整体恢复，失败回滚可重试 ----------------

    @Transactional
    public SafetyDtos.EventView release(Long id, SafetyDtos.OperatorReq req) {
        SafetyEvent event = lockEvent(id);
        String operator = requireName(req.operator(), "值班经理");
        if ("RELEASED".equals(event.getStatus())) {
            throw new BizException("事件已放行，请勿重复操作");
        }
        if (!"CLEARED".equals(event.getStatus())) {
            throw new BizException("教练与器材管理员复核都通过后，才能执行复射放行");
        }

        List<SafetyEventLane> laneItems = laneItemRepository.findByEventId(id);
        List<SafetyEventRound> roundItems = roundItemRepository.findByEventId(id);
        List<SafetyEventEquipment> equipItems = equipItemRepository.findByEventId(id);

        // 以下任一步失败都会回滚整个事务：事件保持「待放行」、资源保持锁定，可安全重试
        for (SafetyEventLane item : laneItems) {
            Lane lane = laneRepository.findByIdForUpdate(item.getLaneId())
                    .orElseThrow(() -> new BizException("箭道不存在：id=" + item.getLaneId()));
            if (!"LOCKED".equals(lane.getStatus())) {
                throw new BizException("箭道 [" + lane.getLaneNo() + "] 锁定状态异常，放行已中止，请重试");
            }
            lane.setStatus(item.getPrevStatus());
            laneRepository.save(lane);
            item.setRestored(true);
            laneItemRepository.save(item);
        }
        for (SafetyEventRound item : roundItems) {
            Round round = roundRepository.findByIdForUpdate(item.getRoundId())
                    .orElseThrow(() -> new BizException("回合不存在：id=" + item.getRoundId()));
            if (!"PAUSED".equals(round.getStatus())) {
                throw new BizException("回合 [" + round.getRoundNo() + "] 暂停状态异常，放行已中止，请重试");
            }
            round.setStatus(item.getPrevStatus());
            roundRepository.save(round);
            item.setRestored(true);
            roundItemRepository.save(item);
        }
        for (SafetyEventEquipment item : equipItems) {
            Equipment equip = equipmentRepository.findByIdForUpdate(item.getEquipmentId())
                    .orElseThrow(() -> new BizException("器材不存在：id=" + item.getEquipmentId()));
            if (!"LOCKED".equals(equip.getStatus())) {
                throw new BizException("器材 [" + equip.getEquipCode() + "] 锁定状态异常，放行已中止，请重试");
            }
            equip.setStatus(item.getPrevStatus());
            equipmentRepository.save(equip);
            item.setRestored(true);
            equipItemRepository.save(item);
        }

        event.setStatus("RELEASED");
        event.setReleasedBy(operator);
        event.setReleasedAt(LocalDateTime.now());
        eventRepository.save(event);
        log(id, "RELEASE", operator, "MANAGER",
                "复射放行：恢复箭道 " + laneItems.size() + " 条、回合 " + roundItems.size() + " 个、器材 " + equipItems.size() + " 件");
        return toView(event);
    }

    // ---------------- 内部工具 ----------------

    /** 锁事件行（FOR UPDATE）：复核与放行的并发请求在此串行化 */
    private SafetyEvent lockEvent(Long id) {
        return eventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("停射事件不存在：id=" + id));
    }

    private String requireName(String name, String who) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("请填写" + who + "姓名");
        }
        if (trimmed.length() > 32) {
            throw new BizException(who + "姓名过长");
        }
        return trimmed;
    }

    private void log(Long eventId, String action, String operator, String role, String detail) {
        SafetyEventLog entry = new SafetyEventLog();
        entry.setEventId(eventId);
        entry.setAction(action);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private String nextEventNo() {
        String no = "S" + LocalDateTime.now().format(NO_STAMP);
        int seq = 1;
        while (eventRepository.existsByEventNo(no)) {
            no = "S" + LocalDateTime.now().format(NO_STAMP) + seq;
            seq += 1;
        }
        return no;
    }

    private SafetyDtos.EventSummary toSummary(SafetyEvent event) {
        return new SafetyDtos.EventSummary(
                event.getId(),
                event.getEventNo(),
                event.getReason(),
                RangeDict.safetyReasonName(event.getReason()),
                event.getSeverity(),
                RangeDict.safetySeverityName(event.getSeverity()),
                event.getStatus(),
                RangeDict.safetyStatusName(event.getStatus()),
                event.getCreatedBy(),
                event.getCreatedAt(),
                laneItemRepository.findByEventId(event.getId()).size(),
                equipItemRepository.findByEventId(event.getId()).size(),
                roundItemRepository.findByEventId(event.getId()).size(),
                event.getReleasedBy(),
                event.getReleasedAt());
    }

    private SafetyDtos.EventView toView(SafetyEvent event) {
        Long id = event.getId();

        List<SafetyEventLane> laneItems = laneItemRepository.findByEventId(id);
        Map<Long, Lane> laneMap = laneItems.isEmpty()
                ? Map.of()
                : laneRepository.findAllById(laneItems.stream().map(SafetyEventLane::getLaneId).toList())
                        .stream().collect(Collectors.toMap(Lane::getId, Function.identity()));
        List<SafetyDtos.LaneItem> lanes = laneItems.stream().map(item -> {
            Lane lane = laneMap.get(item.getLaneId());
            String current = lane == null ? "" : lane.getStatus();
            return new SafetyDtos.LaneItem(
                    item.getLaneId(),
                    lane == null ? "" : lane.getLaneNo(),
                    item.getPrevStatus(),
                    RangeDict.laneStatusName(item.getPrevStatus()),
                    current,
                    RangeDict.laneStatusName(current),
                    item.getRestored());
        }).toList();

        List<SafetyEventEquipment> equipItems = equipItemRepository.findByEventId(id);
        Map<Long, Equipment> equipMap = equipItems.isEmpty()
                ? Map.of()
                : equipmentRepository.findAllById(equipItems.stream().map(SafetyEventEquipment::getEquipmentId).toList())
                        .stream().collect(Collectors.toMap(Equipment::getId, Function.identity()));
        List<SafetyDtos.EquipItem> equips = equipItems.stream().map(item -> {
            Equipment equip = equipMap.get(item.getEquipmentId());
            String current = equip == null ? "" : equip.getStatus();
            return new SafetyDtos.EquipItem(
                    item.getEquipmentId(),
                    equip == null ? "" : equip.getEquipCode(),
                    equip == null ? "" : RangeDict.equipTypeName(equip.getType()),
                    item.getPrevStatus(),
                    RangeDict.equipStatusName(item.getPrevStatus()),
                    current,
                    RangeDict.equipStatusName(current),
                    item.getRestored());
        }).toList();

        List<SafetyEventRound> roundItems = roundItemRepository.findByEventId(id);
        Map<Long, Round> roundMap = roundItems.isEmpty()
                ? Map.of()
                : roundRepository.findAllById(roundItems.stream().map(SafetyEventRound::getRoundId).toList())
                        .stream().collect(Collectors.toMap(Round::getId, Function.identity()));
        List<SafetyDtos.RoundItem> rounds = roundItems.stream().map(item -> {
            Round round = roundMap.get(item.getRoundId());
            String current = round == null ? "" : round.getStatus();
            return new SafetyDtos.RoundItem(
                    item.getRoundId(),
                    round == null ? "" : round.getRoundNo(),
                    round == null || round.getMember() == null ? "" : round.getMember().getName(),
                    round == null || round.getLane() == null ? "" : round.getLane().getLaneNo(),
                    item.getPrevStatus(),
                    RangeDict.roundStatusName(item.getPrevStatus()),
                    current,
                    RangeDict.roundStatusName(current),
                    round == null ? 0 : round.getArrows().size(),
                    round == null ? 0 : round.getArrowCount(),
                    item.getRestored());
        }).toList();

        List<SafetyDtos.ReviewItem> reviews = reviewRepository.findByEventIdOrderByReviewRoundDescItemAsc(id)
                .stream()
                .map(review -> new SafetyDtos.ReviewItem(
                        review.getItem(),
                        RangeDict.reviewItemName(review.getItem()),
                        review.getReviewRound(),
                        review.getConclusion(),
                        review.getConclusion() == null ? "待复核" : RangeDict.conclusionName(review.getConclusion()),
                        review.getReviewer(),
                        review.getReviewedAt(),
                        review.getNote()))
                .toList();

        List<SafetyDtos.LogItem> logs = logRepository.findByEventIdOrderByIdAsc(id)
                .stream()
                .map(entry -> new SafetyDtos.LogItem(
                        entry.getAction(),
                        RangeDict.safetyActionName(entry.getAction()),
                        entry.getOperator(),
                        entry.getRole(),
                        RangeDict.safetyRoleName(entry.getRole()),
                        entry.getDetail(),
                        entry.getCreatedAt()))
                .toList();

        return new SafetyDtos.EventView(
                id,
                event.getEventNo(),
                event.getReason(),
                RangeDict.safetyReasonName(event.getReason()),
                event.getSeverity(),
                RangeDict.safetySeverityName(event.getSeverity()),
                event.getDescription(),
                event.getStatus(),
                RangeDict.safetyStatusName(event.getStatus()),
                event.getReviewRound(),
                event.getCreatedBy(),
                event.getCreatedAt(),
                event.getReleasedBy(),
                event.getReleasedAt(),
                lanes,
                equips,
                rounds,
                reviews,
                logs);
    }
}
