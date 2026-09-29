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
import com.archery.range.dto.EquipmentDtos;
import com.archery.range.service.EquipmentService;

import jakarta.validation.Valid;

/**
 * 模块五：器材租赁（编号 / 类型 / 品牌 / 租金 / 状态 + 租借归还报修）。
 */
@RestController
@RequestMapping("/api")
public class EquipmentController {

    private final EquipmentService equipmentService;

    public EquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    @GetMapping("/equipment")
    public ApiResponse<List<EquipmentDtos.EquipmentView>> list(@RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        if (type != null && !type.isBlank()) {
            return ApiResponse.success(equipmentService.listByType(type));
        }
        if (status != null && !status.isBlank()) {
            return ApiResponse.success(equipmentService.listByStatus(status));
        }
        return ApiResponse.success(equipmentService.list());
    }

    @PostMapping("/equipment")
    public ApiResponse<EquipmentDtos.EquipmentView> create(@Valid @RequestBody EquipmentDtos.EquipmentSaveReq req) {
        return ApiResponse.success("器材已入库", equipmentService.create(req));
    }

    @PostMapping("/equipment/{id}/rent")
    public ApiResponse<EquipmentDtos.EquipmentView> rent(@PathVariable Long id,
            @Valid @RequestBody EquipmentDtos.RentReq req) {
        return ApiResponse.success("租借成功", equipmentService.rent(id, req));
    }

    @PostMapping("/equipment/{id}/return")
    public ApiResponse<EquipmentDtos.EquipmentView> giveBack(@PathVariable Long id) {
        return ApiResponse.success("已归还", equipmentService.giveBack(id));
    }

    @PostMapping("/equipment/{id}/repair")
    public ApiResponse<EquipmentDtos.EquipmentView> repair(@PathVariable Long id) {
        return ApiResponse.success("状态已更新", equipmentService.toggleRepair(id));
    }

    @GetMapping("/equipment/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("types", List.of(
                Map.of("code", "RECURVE", "name", RangeDict.equipTypeName("RECURVE")),
                Map.of("code", "COMPOUND", "name", RangeDict.equipTypeName("COMPOUND")),
                Map.of("code", "TRADITIONAL", "name", RangeDict.equipTypeName("TRADITIONAL")),
                Map.of("code", "GEAR", "name", RangeDict.equipTypeName("GEAR")),
                Map.of("code", "ARROW", "name", RangeDict.equipTypeName("ARROW"))));
        options.put("statuses", List.of(
                Map.of("code", "INSTOCK", "name", RangeDict.equipStatusName("INSTOCK")),
                Map.of("code", "RENTED", "name", RangeDict.equipStatusName("RENTED")),
                Map.of("code", "REPAIR", "name", RangeDict.equipStatusName("REPAIR")),
                Map.of("code", "LOCKED", "name", RangeDict.equipStatusName("LOCKED"))));
        return ApiResponse.success(options);
    }
}
