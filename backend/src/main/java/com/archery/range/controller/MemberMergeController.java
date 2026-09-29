package com.archery.range.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.dto.MemberMergeDtos;
import com.archery.range.service.MemberMergeService;

import jakarta.validation.Valid;

/**
 * 模块九：重复会员档案合并。
 *
 * 值班经理选择保留档案与来源档案 → 预演（逐项差异 + 互斥项 + 合并结果 + 数据指纹）
 * → 确认（以预演版本为依据，预演后任一相关数据变化即拒绝）。
 * 普通会员只能查询自己的合并结果与从旧卡号进入时的跳转信息，不能发起 / 确认 / 撤销。
 */
@RestController
@RequestMapping("/api")
public class MemberMergeController {

    private final MemberMergeService mergeService;

    public MemberMergeController(MemberMergeService mergeService) {
        this.mergeService = mergeService;
    }

    /** 合并预演：固化快照与指纹，不落任何业务数据 */
    @PostMapping("/member-merges/preview")
    public ApiResponse<MemberMergeDtos.MergePreviewView> preview(
            @Valid @RequestBody MemberMergeDtos.MergePreviewReq req) {
        return ApiResponse.success("合并预演已生成", mergeService.preview(req));
    }

    /** 确认合并：必须以预演版本为依据；并发确认同一来源只能成功一次 */
    @PostMapping("/member-merges/confirm")
    public ApiResponse<MemberMergeDtos.MergeDetailView> confirm(
            @Valid @RequestBody MemberMergeDtos.MergeConfirmReq req) {
        return ApiResponse.success("重复档案已合并，来源档案已封存", mergeService.confirm(req));
    }

    /** 合并单详情（含预演快照与合并轨迹） */
    @GetMapping("/member-merges/{id}")
    public ApiResponse<MemberMergeDtos.MergeDetailView> detail(@PathVariable Long id) {
        return ApiResponse.success(mergeService.detail(id));
    }

    /** 会员的合并结果：保留卡 / 已封存来源卡都能查到自己的合并轨迹（普通会员只读） */
    @GetMapping("/members/{memberId}/merges")
    public ApiResponse<List<MemberMergeDtos.MergeSummary>> listByMember(@PathVariable Long memberId) {
        return ApiResponse.success(mergeService.listByMember(memberId));
    }

    /** 旧卡号进入：正常卡返回自身；已封存卡返回保留档案跳转信息（合并时间 / 经理 / 原因） */
    @GetMapping("/members/by-card")
    public ApiResponse<MemberMergeDtos.CardEntryView> entryByCard(@RequestParam String cardNo) {
        return ApiResponse.success(mergeService.entryByCardNo(cardNo));
    }
}
