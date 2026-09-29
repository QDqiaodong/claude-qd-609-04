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
import com.archery.range.dto.MergeDtos;
import com.archery.range.service.MergeService;

import jakarta.validation.Valid;

/**
 * 模块九：重复会员档案合并（值班经理：选保留档案 + 来源档案 → 预演 → 确认）。
 *
 * 普通会员只通过 GET 轨迹接口查看自己的合并结果，没有发起 / 撤销入口；
 * 合并不可撤销，来源档案封存后仅保留旧卡号跳转与轨迹可查。
 */
@RestController
@RequestMapping("/api/member-merges")
public class MemberMergeController {

    private final MergeService mergeService;

    public MemberMergeController(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    /** 发起合并预演：两侧画像、互斥项清单、去重处理与合并后金额 / 等级 / 迁移量 */
    @PostMapping("/preview")
    public ApiResponse<MergeDtos.MergePreview> preview(@Valid @RequestBody MergeDtos.MergePreviewReq req) {
        return ApiResponse.success("合并预演已生成，请核对互斥项与合并结果", mergeService.preview(req));
    }

    /** 查看某一版预演（确认页刷新 / 二次核对） */
    @GetMapping("/preview/{id}")
    public ApiResponse<MergeDtos.MergePreview> preview(@PathVariable Long id) {
        return ApiResponse.success(mergeService.getPreview(id));
    }

    /** 确认合并：必须以预演版本为依据，数据变化 / 并发冲突 / 迁移失败都会被拒绝或整体回滚 */
    @PostMapping("/confirm")
    public ApiResponse<MergeDtos.MergeResult> confirm(@Valid @RequestBody MergeDtos.MergeConfirmReq req) {
        return ApiResponse.success("合并完成，来源档案已封存为历史档案", mergeService.confirmMerge(req));
    }

    /** 合并轨迹：会员详情 / 列表入口（会员本人仅能查看自己的结果） */
    @GetMapping("/trails/{memberId}")
    public ApiResponse<List<MergeDtos.MergeTrailView>> trails(@PathVariable Long memberId) {
        return ApiResponse.success(mergeService.trails(memberId));
    }

    /** 旧卡号入口：来源档案封存后按旧卡号查询，返回保留档案跳转信息与合并时间 / 原因 / 操作者 */
    @GetMapping("/lookup")
    public ApiResponse<MergeDtos.ArchivedRedirect> lookup(@RequestParam String cardNo) {
        return ApiResponse.success(mergeService.lookupByCardNo(cardNo));
    }
}
