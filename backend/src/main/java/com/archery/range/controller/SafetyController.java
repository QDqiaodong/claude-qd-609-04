package com.archery.range.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.SafetyDtos;
import com.archery.range.service.SafetyService;

import jakarta.validation.Valid;

/**
 * 模块六：安全停射联锁台。
 *
 * 值班经理建立停射事件 → 受影响箭道 / 回合 / 器材立即联锁；
 * 教练与器材管理员分项复核双双通过 → 值班经理复射放行，整体恢复原状态。
 */
@RestController
@RequestMapping("/api/safety")
public class SafetyController {

    private final SafetyService safetyService;

    public SafetyController(SafetyService safetyService) {
        this.safetyService = safetyService;
    }

    /** 事件列表：active=true 只看未放行 */
    @GetMapping("/events")
    public ApiResponse<List<SafetyDtos.EventSummary>> list(@RequestParam(required = false) Boolean active) {
        return ApiResponse.success(safetyService.list(Boolean.TRUE.equals(active)));
    }

    /** 事件详情：受影响资源 + 复核记录 + 完整状态轨迹 */
    @GetMapping("/events/{id}")
    public ApiResponse<SafetyDtos.EventView> detail(@PathVariable Long id) {
        return ApiResponse.success(safetyService.detail(id));
    }

    /** 建立停射事件（值班经理）：立即联锁受影响资源 */
    @PostMapping("/events")
    public ApiResponse<SafetyDtos.EventView> create(@Valid @RequestBody SafetyDtos.CreateReq req) {
        return ApiResponse.success("停射事件已建立，受影响资源已安全锁定", safetyService.create(req));
    }

    /** 发起分项复核（值班经理）：待处置 → 分项复核 */
    @PostMapping("/events/{id}/start-review")
    public ApiResponse<SafetyDtos.EventView> startReview(@PathVariable Long id,
            @Valid @RequestBody SafetyDtos.OperatorReq req) {
        return ApiResponse.success("已发起分项复核", safetyService.startReview(id, req));
    }

    /** 分项复核（教练 / 器材管理员）：同一复核项只形成一份最终结论 */
    @PostMapping("/events/{id}/review")
    public ApiResponse<SafetyDtos.EventView> review(@PathVariable Long id,
            @Valid @RequestBody SafetyDtos.ReviewReq req) {
        return ApiResponse.success("复核结论已记录", safetyService.review(id, req));
    }

    /** 复射放行（值班经理）：两项复核都通过后执行，整体恢复且只成功一次 */
    @PostMapping("/events/{id}/release")
    public ApiResponse<SafetyDtos.EventView> release(@PathVariable Long id,
            @Valid @RequestBody SafetyDtos.OperatorReq req) {
        return ApiResponse.success("已复射放行，受影响资源恢复原状态", safetyService.release(id, req));
    }

    /** 联锁台词典：原因 / 严重级别 / 角色 / 复核项 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("reasons", List.of(
                Map.of("code", "LANE_DEVICE", "name", RangeDict.safetyReasonName("LANE_DEVICE")),
                Map.of("code", "PERSON_INTRUSION", "name", RangeDict.safetyReasonName("PERSON_INTRUSION")),
                Map.of("code", "EQUIP_SUSPECT", "name", RangeDict.safetyReasonName("EQUIP_SUSPECT")),
                Map.of("code", "OTHER", "name", RangeDict.safetyReasonName("OTHER"))));
        options.put("severities", List.of(
                Map.of("code", "NOTICE", "name", RangeDict.safetySeverityName("NOTICE")),
                Map.of("code", "MAJOR", "name", RangeDict.safetySeverityName("MAJOR")),
                Map.of("code", "CRITICAL", "name", RangeDict.safetySeverityName("CRITICAL"))));
        options.put("roles", List.of(
                Map.of("code", "MANAGER", "name", RangeDict.safetyRoleName("MANAGER")),
                Map.of("code", "COACH", "name", RangeDict.safetyRoleName("COACH")),
                Map.of("code", "KEEPER", "name", RangeDict.safetyRoleName("KEEPER"))));
        options.put("reviewItems", List.of(
                Map.of("code", "RANGE", "name", RangeDict.reviewItemName("RANGE"), "role", "COACH"),
                Map.of("code", "EQUIPMENT", "name", RangeDict.reviewItemName("EQUIPMENT"), "role", "KEEPER")));
        return ApiResponse.success(options);
    }
}
