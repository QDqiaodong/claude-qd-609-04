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
import com.archery.range.dto.CertDtos;
import com.archery.range.service.CertService;

import jakarta.validation.Valid;

/**
 * 模块八：弓种能力认证。
 *
 * 教练从会员已完成计分回合中划定证据窗口建立申请 → 系统按版本化规则快照预评并进入待复核 →
 * 教练复核通过 / 驳回 / 要求补充证据 → 通过后发放带有效期与适用范围的认证；
 * 认证可撤回、可重新评定取代旧认证，过期自动失效，箭道开台与弓具租借入口据此识别可用状态。
 */
@RestController
@RequestMapping("/api/cert")
public class CertController {

    private final CertService certService;

    public CertController(CertService certService) {
        this.certService = certService;
    }

    /** 表单选项：弓种 / 射距 / 每组箭数 / 需认证射距 / 现行规则 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        return ApiResponse.success(certService.options());
    }

    // ---------------- 规则（版本化） ----------------

    @GetMapping("/rules")
    public ApiResponse<List<CertDtos.RuleView>> rules() {
        return ApiResponse.success(certService.listRules());
    }

    /** 新建规则谱系 v1 */
    @PostMapping("/rules")
    public ApiResponse<CertDtos.RuleView> createRule(@Valid @RequestBody CertDtos.RuleSaveReq req) {
        return ApiResponse.success("规则已发布（v1）", certService.createRule(req));
    }

    /** 发布新版本：上一现行版本自动停用，历史认证仍引用旧版本 */
    @PostMapping("/rules/{id}/new-version")
    public ApiResponse<CertDtos.RuleView> newVersion(@PathVariable Long id,
            @Valid @RequestBody CertDtos.RuleSaveReq req) {
        return ApiResponse.success("新版本已发布，旧版本停用且历史认证不变", certService.newVersion(id, req));
    }

    /** 停用现行规则：不再用于新申请，历史认证不受影响 */
    @PostMapping("/rules/{id}/retire")
    public ApiResponse<CertDtos.RuleView> retireRule(@PathVariable Long id,
            @Valid @RequestBody CertDtos.RuleOperatorReq req) {
        return ApiResponse.success("规则已停用", certService.retireRule(id, req));
    }

    // ---------------- 认证申请 ----------------

    /** 申请列表：可按会员 / 状态筛选 */
    @GetMapping("/applications")
    public ApiResponse<List<CertDtos.AppSummary>> list(@RequestParam(required = false) Long memberId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(certService.listApplications(memberId, status));
    }

    /** 申请详情：规则快照、证据回合、历次评定与状态轨迹 */
    @GetMapping("/applications/{id}")
    public ApiResponse<CertDtos.AppDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(certService.detail(id));
    }

    /** 建立认证申请：证据窗口校验后系统预评，结果为待复核 */
    @PostMapping("/applications")
    public ApiResponse<CertDtos.AppDetail> create(@Valid @RequestBody CertDtos.CreateAppReq req) {
        return ApiResponse.success("申请已建立，系统预评结果为待复核", certService.createApplication(req));
    }

    /** 复核决定：通过 / 驳回 / 要求补充证据（并发只产生一个最终结论） */
    @PostMapping("/applications/{id}/decide")
    public ApiResponse<CertDtos.AppDetail> decide(@PathVariable Long id,
            @Valid @RequestBody CertDtos.DecideReq req) {
        return ApiResponse.success("复核结论已记录", certService.decide(id, req));
    }

    /** 待补充证据：新窗口与回合重新提交，按原规则快照重新评定 */
    @PostMapping("/applications/{id}/resubmit")
    public ApiResponse<CertDtos.AppDetail> resubmit(@PathVariable Long id,
            @Valid @RequestBody CertDtos.ResubmitReq req) {
        return ApiResponse.success("已补充证据并重新评定，等待复核", certService.resubmit(id, req));
    }

    /** 撤回已通过认证（须写原因，会员相关可用状态随即失效） */
    @PostMapping("/applications/{id}/withdraw")
    public ApiResponse<CertDtos.AppDetail> withdraw(@PathVariable Long id,
            @Valid @RequestBody CertDtos.WithdrawReq req) {
        return ApiResponse.success("认证已撤回", certService.withdraw(id, req));
    }

    // ---------------- 会员认证视图 ----------------

    /** 会员详情：当前有效认证 + 全部历史记录 */
    @GetMapping("/members/{memberId}")
    public ApiResponse<CertDtos.MemberCerts> memberCerts(@PathVariable Long memberId) {
        return ApiResponse.success(certService.memberCerts(memberId));
    }
}
