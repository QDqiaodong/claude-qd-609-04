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
import com.archery.range.dto.LaneDtos;
import com.archery.range.service.LaneService;

import jakarta.validation.Valid;

/**
 * 模块一：箭道管理（道号 / 距离 / 箭靶类型 / 状态 / 每小时单价 + 开台收台）。
 */
@RestController
@RequestMapping("/api")
public class LaneController {

    private final LaneService laneService;

    public LaneController(LaneService laneService) {
        this.laneService = laneService;
    }

    @GetMapping("/lanes")
    public ApiResponse<List<LaneDtos.LaneView>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer distance) {
        if (status != null && !status.isBlank()) {
            return ApiResponse.success(laneService.listByStatus(status));
        }
        if (distance != null) {
            return ApiResponse.success(laneService.listByDistance(distance));
        }
        return ApiResponse.success(laneService.list());
    }

    @GetMapping("/lanes/{id}")
    public ApiResponse<LaneDtos.LaneView> get(@PathVariable Long id) {
        return ApiResponse.success(laneService.get(id));
    }

    @PostMapping("/lanes")
    public ApiResponse<LaneDtos.LaneView> create(@Valid @RequestBody LaneDtos.LaneSaveReq req) {
        return ApiResponse.success("箭道已新增", laneService.create(req));
    }

    /** 开台：占用箭道并预扣会员余额 */
    @PostMapping("/lanes/{id}/open")
    public ApiResponse<LaneDtos.LaneView> open(@PathVariable Long id, @RequestBody LaneDtos.LaneOpenReq req) {
        return ApiResponse.success("开台成功", laneService.open(id, req));
    }

    /** 收台 */
    @PostMapping("/lanes/{id}/release")
    public ApiResponse<LaneDtos.LaneView> release(@PathVariable Long id) {
        return ApiResponse.success("已收台", laneService.release(id));
    }

    /** 手工切状态：维护中 / 重新开放 */
    @PostMapping("/lanes/{id}/status")
    public ApiResponse<LaneDtos.LaneView> setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.success(laneService.setStatus(id, body.get("status")));
    }

    /** 下拉用的词典 */
    @GetMapping("/lanes/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("distances", RangeDict.DISTANCES);
        options.put("statuses", List.of(
                Map.of("code", "OPEN", "name", RangeDict.laneStatusName("OPEN")),
                Map.of("code", "OCCUPIED", "name", RangeDict.laneStatusName("OCCUPIED")),
                Map.of("code", "MAINTENANCE", "name", RangeDict.laneStatusName("MAINTENANCE")),
                Map.of("code", "LOCKED", "name", RangeDict.laneStatusName("LOCKED"))));
        options.put("maxOpenHours", RangeDict.MAX_OPEN_HOURS);
        return ApiResponse.success(options);
    }
}
