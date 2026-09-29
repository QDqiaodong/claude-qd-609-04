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
import com.archery.range.common.BizException;
import com.archery.range.dto.MemberDtos;
import com.archery.range.service.MemberService;

import jakarta.validation.Valid;

/**
 * 模块二：会员管理（卡号 / 姓名 / 手机号 / 等级 / 余额 / 注册日期 / 累计消费）。
 */
@RestController
@RequestMapping("/api")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/members")
    public ApiResponse<List<MemberDtos.MemberView>> list(@RequestParam(required = false) String level) {
        if (level != null && !level.isBlank()) {
            return ApiResponse.success(memberService.listByLevel(level));
        }
        return ApiResponse.success(memberService.list());
    }

    @GetMapping("/members/{id}")
    public ApiResponse<MemberDtos.MemberView> get(@PathVariable Long id) {
        return ApiResponse.success(memberService.get(id));
    }

    @PostMapping("/members")
    public ApiResponse<MemberDtos.MemberView> create(@Valid @RequestBody MemberDtos.MemberSaveReq req) {
        return ApiResponse.success("会员已开卡", memberService.create(req));
    }

    @PostMapping("/members/{id}/recharge")
    public ApiResponse<MemberDtos.MemberView> recharge(@PathVariable Long id,
            @Valid @RequestBody MemberDtos.RechargeReq req) {
        return ApiResponse.success("充值成功", memberService.recharge(id, req));
    }

    /** 会员下拉（开台 / 记分 / 报名 / 租借共用） */
    @GetMapping("/members/options")
    public ApiResponse<List<MemberDtos.MemberView>> options() {
        List<MemberDtos.MemberView> list = memberService.list();
        if (list.isEmpty()) {
            throw new BizException("还没有会员，请先开卡");
        }
        return ApiResponse.success(list);
    }
}
