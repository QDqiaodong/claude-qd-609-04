package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Lane;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.LaneDtos;
import com.archery.range.repository.LaneRepository;

@Service
public class LaneService {

    private final LaneRepository laneRepository;
    private final MemberService memberService;
    private final CertService certService;

    public LaneService(LaneRepository laneRepository, MemberService memberService, CertService certService) {
        this.laneRepository = laneRepository;
        this.memberService = memberService;
        this.certService = certService;
    }

    @Transactional(readOnly = true)
    public List<LaneDtos.LaneView> list() {
        return laneRepository.findAllByOrderByLaneNoAsc().stream().map(LaneService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<LaneDtos.LaneView> listByStatus(String status) {
        return laneRepository.findByStatusOrderByLaneNoAsc(status).stream().map(LaneService::toView).toList();
    }

    @Transactional(readOnly = true)
    public LaneDtos.LaneView get(Long id) {
        return toView(require(id));
    }

    @Transactional(readOnly = true)
    public List<LaneDtos.LaneView> listByDistance(Integer distance) {
        return laneRepository.findByDistanceOrderByLaneNoAsc(distance).stream().map(LaneService::toView).toList();
    }

    @Transactional
    public LaneDtos.LaneView create(LaneDtos.LaneSaveReq req) {
        if (laneRepository.existsByLaneNo(req.laneNo())) {
            throw new BizException("道号已存在：" + req.laneNo());
        }
        if (!RangeDict.isValidDistance(req.distance())) {
            throw new BizException("箭道距离只能是 10 / 18 / 30 / 50 米");
        }
        Lane lane = new Lane();
        lane.setLaneNo(req.laneNo());
        lane.setDistance(req.distance());
        lane.setTargetType(req.targetType());
        lane.setStatus("OPEN");
        lane.setHourlyPrice(req.hourlyPrice().setScale(2, RoundingMode.HALF_UP));
        return toView(laneRepository.save(lane));
    }

    /**
     * 开台：只有「开放」的箭道能开台，按 单价 × 时长 × 会员折扣 预扣余额。
     * 安全锁定的箭道禁止开台（锁行与联锁恢复互斥）。
     */
    @Transactional
    public LaneDtos.LaneView open(Long id, LaneDtos.LaneOpenReq req) {
        Lane lane = requireForUpdate(id);
        rejectIfLocked(lane, "开台");
        if (!"OPEN".equals(lane.getStatus())) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 当前为「"
                    + RangeDict.laneStatusName(lane.getStatus()) + "」，不能开台");
        }
        if (req.hours() == null || req.hours() < 1 || req.hours() > RangeDict.MAX_OPEN_HOURS) {
            throw new BizException("开台时长需在 1 ~ " + RangeDict.MAX_OPEN_HOURS + " 小时之间");
        }
        Member member = memberService.require(req.memberId());
        // 30 / 50 米为认证射距：必须持有覆盖该射距的当前有效弓种认证（过期 / 撤回 / 被取代立即失效）
        certService.requireDistanceCert(member, lane.getDistance());
        BigDecimal cost = lane.getHourlyPrice()
                .multiply(BigDecimal.valueOf(req.hours()))
                .multiply(RangeDict.memberDiscount(member.getLevel()))
                .setScale(2, RoundingMode.HALF_UP);
        memberService.charge(member, cost, "开台 " + req.hours() + " 小时");

        lane.setStatus("OCCUPIED");
        lane.setOccupantId(member.getId());
        lane.setOccupantName(member.getName());
        lane.setOpenedAt(LocalDateTime.now());
        lane.setPlannedHours(req.hours());
        return toView(laneRepository.save(lane));
    }

    /** 收台：把占用中的箭道放回开放。安全锁定中禁止收台。 */
    @Transactional
    public LaneDtos.LaneView release(Long id) {
        Lane lane = requireForUpdate(id);
        rejectIfLocked(lane, "收台");
        if (!"OCCUPIED".equals(lane.getStatus())) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 当前无人占用，无需收台");
        }
        lane.setStatus("OPEN");
        lane.setOccupantId(null);
        lane.setOccupantName(null);
        lane.setOpenedAt(null);
        lane.setPlannedHours(null);
        return toView(laneRepository.save(lane));
    }

    /** 状态切换：开放 → 维护（保养）；维护 → 开放（修好）。占用中不允许改维护；安全锁定中禁止变更。 */
    @Transactional
    public LaneDtos.LaneView setStatus(Long id, String status) {
        Lane lane = requireForUpdate(id);
        rejectIfLocked(lane, "变更状态");
        if (!"OPEN".equals(status) && !"MAINTENANCE".equals(status)) {
            throw new BizException("只能手工切换到「开放」或「维护」状态");
        }
        if ("MAINTENANCE".equals(status) && "OCCUPIED".equals(lane.getStatus())) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 正在使用中，请先收台再转维护");
        }
        if ("OPEN".equals(status) && lane.getStatus().equals("OPEN")) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 已经是开放状态");
        }
        if ("MAINTENANCE".equals(status)) {
            lane.setOccupantId(null);
            lane.setOccupantName(null);
            lane.setOpenedAt(null);
            lane.setPlannedHours(null);
        }
        lane.setStatus(status);
        return toView(laneRepository.save(lane));
    }

    @Transactional(readOnly = true)
    public Lane require(Long id) {
        return laneRepository.findById(id)
                .orElseThrow(() -> new BizException("箭道不存在：id=" + id));
    }

    /** 写操作入口：悲观锁箭道行，与安全联锁的锁定 / 恢复互斥 */
    private Lane requireForUpdate(Long id) {
        return laneRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("箭道不存在：id=" + id));
    }

    private static void rejectIfLocked(Lane lane, String action) {
        if ("LOCKED".equals(lane.getStatus())) {
            throw new BizException("箭道 [" + lane.getLaneNo() + "] 处于安全锁定（停射事件未放行），禁止" + action);
        }
    }

    private static LaneDtos.LaneView toView(Lane lane) {
        return new LaneDtos.LaneView(
                lane.getId(),
                lane.getLaneNo(),
                lane.getDistance(),
                lane.getTargetType(),
                lane.getStatus(),
                RangeDict.laneStatusName(lane.getStatus()),
                lane.getHourlyPrice(),
                lane.getOccupantId(),
                lane.getOccupantName(),
                lane.getOpenedAt(),
                lane.getPlannedHours());
    }
}
