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
import com.archery.range.dto.RoundDtos;
import com.archery.range.service.PersonalBestRepairService;
import com.archery.range.service.RoundService;

import jakarta.validation.Valid;

/**
 * 模块三：计分回合（核心）。
 *
 * 写入路线是「有序集合」：POST /rounds/{id}/shots 每记一支箭，
 * 就在 Round.arrows（@OneToMany + @JoinTable + @OrderColumn(name="shot_index")）尾部 append 一条，
 * GET /rounds/{id} 按 shot_index 顺序原样返回整串箭支。
 */
@RestController
@RequestMapping("/api")
public class RoundController {

    private final RoundService roundService;
    private final PersonalBestRepairService repairService;

    public RoundController(RoundService roundService, PersonalBestRepairService repairService) {
        this.roundService = roundService;
        this.repairService = repairService;
    }

    @GetMapping("/rounds")
    public ApiResponse<List<RoundDtos.RoundView>> list(@RequestParam(required = false) Long memberId,
            @RequestParam(required = false) String status) {
        if (memberId != null) {
            return ApiResponse.success(roundService.listByMember(memberId));
        }
        if (status != null && !status.isBlank()) {
            return ApiResponse.success(roundService.listByStatus(status));
        }
        return ApiResponse.success(roundService.list());
    }

    /** 成绩单：回合 + 按 shot_index 顺序的每一支箭 */
    @GetMapping("/rounds/{id}")
    public ApiResponse<RoundDtos.RoundDetailView> detail(@PathVariable Long id) {
        return ApiResponse.success(roundService.detail(id));
    }

    @PostMapping("/rounds")
    public ApiResponse<RoundDtos.RoundDetailView> start(@Valid @RequestBody RoundDtos.RoundStartReq req) {
        return ApiResponse.success("回合已开打", roundService.start(req));
    }

    /** 记一支箭：append 到有序集合尾部 */
    @PostMapping("/rounds/{id}/shots")
    public ApiResponse<RoundDtos.RoundDetailView> shoot(@PathVariable Long id,
            @Valid @RequestBody RoundDtos.ShotReq req) {
        return ApiResponse.success("已记一支箭", roundService.shoot(id, req));
    }

    /** 撤销最后一支箭 */
    @PostMapping("/rounds/{id}/undo")
    public ApiResponse<RoundDtos.RoundDetailView> undo(@PathVariable Long id) {
        return ApiResponse.success("已撤销最后一支箭", roundService.undo(id));
    }

    /** 打满一组后提交，判定个人最好成绩 */
    @PostMapping("/rounds/{id}/submit")
    public ApiResponse<RoundDtos.RoundDetailView> submit(@PathVariable Long id) {
        return ApiResponse.success("回合已提交", roundService.submit(id));
    }

    /** 会员详情：按可比组别（弓种 + 射距 + 箭数）列出当前每条个人最好 */
    @GetMapping("/members/{memberId}/personal-bests")
    public ApiResponse<RoundDtos.MemberBestsView> memberBests(@PathVariable Long memberId) {
        return ApiResponse.success(repairService.memberBests(memberId));
    }

    /** 历史修复预演：按可比组别重算，不落库，返回将要改动的标记数与无法参与比较的回合 */
    @GetMapping("/personal-bests/repair")
    public ApiResponse<RoundDtos.RepairReport> previewRepair() {
        return ApiResponse.success(repairService.preview());
    }

    /**
     * 历史修复执行：只重写 personal_best 标记（箭支顺序 / 环值 / 总分 / 平均环 / 认证证据 /
     * 完成时间均不动）。结果幂等，可重复执行；第二次起 marksChanged 恒为 0。
     */
    @PostMapping("/personal-bests/repair")
    public ApiResponse<RoundDtos.RepairReport> repair() {
        return ApiResponse.success("个人最好成绩历史修复已完成", repairService.repair());
    }

    /** 计分键盘配置：环数键 + 可选的每组箭支数 */
    @GetMapping("/rounds/keypad")
    public ApiResponse<Map<String, Object>> keypad() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("rings", RangeDict.KEYPAD_RINGS);
        config.put("groupSizes", RangeDict.GROUP_SIZES);
        return ApiResponse.success(config);
    }
}
