package com.archery.range.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.archery.range.common.ApiResponse;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.TournamentDtos;
import com.archery.range.service.TournamentService;

import jakarta.validation.Valid;

/**
 * 模块七：馆内团体淘汰赛计分台（与日常计分完全独立的一套台）。
 *
 * 值班经理：建立赛事、管理参赛队（报名中）、开赛生成对阵；
 * 裁判：进入场次记局、确认局、撤回最后一局、加赛箭、确认胜者（胜者自动带入下一轮）；
 * 普通会员：只读。角色由请求体 role/operator 携带，服务端强制鉴权。
 */
@RestController
@RequestMapping("/api/tournament")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    // ---------------- 赛事总览 ----------------

    @GetMapping("/list")
    public ApiResponse<List<TournamentDtos.TournamentSummary>> list() {
        return ApiResponse.success(tournamentService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<TournamentDtos.TournamentDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(tournamentService.detail(id));
    }

    /** 建赛台词典：队数 / 局数 / 每局箭数 / 角色 / 环数键 */
    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("teamSizes", RangeDict.TEAM_SIZES);
        options.put("endsPerMatch", RangeDict.ENDS_PER_MATCH);
        options.put("arrowsPerEnd", RangeDict.ARROWS_PER_END);
        options.put("membersPerTeam", RangeDict.MEMBERS_PER_TEAM);
        options.put("rings", RangeDict.ALL_RINGS);
        options.put("roles", List.of(
                Map.of("code", "MANAGER", "name", RangeDict.tournamentRoleName("MANAGER")),
                Map.of("code", "REFEREE", "name", RangeDict.tournamentRoleName("REFEREE")),
                Map.of("code", "MEMBER", "name", RangeDict.tournamentRoleName("MEMBER"))));
        return ApiResponse.success(options);
    }

    // ---------------- 值班经理：赛事与参赛队 ----------------

    @PostMapping("/create")
    public ApiResponse<TournamentDtos.TournamentDetail> create(@Valid @RequestBody TournamentDtos.CreateReq req) {
        return ApiResponse.success("赛事已建立", tournamentService.create(req));
    }

    @PostMapping("/{id}/teams")
    public ApiResponse<TournamentDtos.TournamentDetail> addTeam(@PathVariable Long id,
            @Valid @RequestBody TournamentDtos.TeamReq req) {
        return ApiResponse.success("参赛队已加入", tournamentService.addTeam(id, req));
    }

    @PostMapping("/{id}/teams/{teamId}")
    public ApiResponse<TournamentDtos.TournamentDetail> updateTeam(@PathVariable Long id,
            @PathVariable Long teamId,
            @Valid @RequestBody TournamentDtos.TeamReq req) {
        return ApiResponse.success("参赛队已调整", tournamentService.updateTeam(id, teamId, req));
    }

    @PostMapping("/{id}/teams/{teamId}/remove")
    public ApiResponse<TournamentDtos.TournamentDetail> removeTeam(@PathVariable Long id,
            @PathVariable Long teamId,
            @Valid @RequestBody TournamentDtos.OperatorReq req) {
        return ApiResponse.success("参赛队已移除", tournamentService.removeTeam(id, teamId, req));
    }

    /** 开赛：后端一次性生成完整淘汰对阵并冻结队伍与对阵关系 */
    @PostMapping("/{id}/start")
    public ApiResponse<TournamentDtos.TournamentDetail> start(@PathVariable Long id,
            @Valid @RequestBody TournamentDtos.OperatorReq req) {
        return ApiResponse.success("已开赛，对阵由系统生成并冻结", tournamentService.start(id, req));
    }

    // ---------------- 裁判：单场计分 ----------------

    @GetMapping("/matches/{matchId}")
    public ApiResponse<TournamentDtos.MatchDetail> matchDetail(@PathVariable Long matchId) {
        return ApiResponse.success(tournamentService.matchDetail(matchId));
    }

    /** 记局内一支箭（未确认局可覆盖更正） */
    @PostMapping("/matches/{matchId}/arrows")
    public ApiResponse<TournamentDtos.MatchDetail> recordArrow(@PathVariable Long matchId,
            @Valid @RequestBody TournamentDtos.ArrowReq req) {
        return ApiResponse.success("箭值已记录", tournamentService.recordArrow(matchId, req));
    }

    /** 确认当前局（三人规定箭数录齐才可确认，确认后不可改写） */
    @PostMapping("/matches/{matchId}/confirm-end")
    public ApiResponse<TournamentDtos.MatchDetail> confirmEnd(@PathVariable Long matchId,
            @Valid @RequestBody TournamentDtos.ActionReq req) {
        return ApiResponse.success("本局已确认", tournamentService.confirmEnd(matchId, req));
    }

    /** 撤回当前未结束比赛的最后一局（必须填写原因，全程留痕） */
    @PostMapping("/matches/{matchId}/retract-end")
    public ApiResponse<TournamentDtos.MatchDetail> retractEnd(@PathVariable Long matchId,
            @Valid @RequestBody TournamentDtos.RetractReq req) {
        return ApiResponse.success("已撤回最后一局并保留撤回轨迹", tournamentService.retractLastEnd(matchId, req));
    }

    /** 加赛箭：当前轮某队员射一支 */
    @PostMapping("/matches/{matchId}/shootoff/arrows")
    public ApiResponse<TournamentDtos.MatchDetail> shootoffArrow(@PathVariable Long matchId,
            @Valid @RequestBody TournamentDtos.ShootoffArrowReq req) {
        return ApiResponse.success("加赛箭值已记录", tournamentService.recordShootoffArrow(matchId, req));
    }

    /** 锁定当前加赛轮：先比加赛总分、再比 X 数，仍平自动开下一轮 */
    @PostMapping("/matches/{matchId}/shootoff/lock")
    public ApiResponse<TournamentDtos.MatchDetail> lockShootoff(@PathVariable Long matchId,
            @Valid @RequestBody TournamentDtos.ActionReq req) {
        return ApiResponse.success("加赛轮已锁定", tournamentService.lockShootoffRound(matchId, req));
    }

    /** 确认胜者：已确认不可倒退，胜者自动带入下一轮 */
    @PostMapping("/matches/{matchId}/confirm-winner")
    public ApiResponse<TournamentDtos.MatchDetail> confirmWinner(@PathVariable Long matchId,
            @Valid @RequestBody TournamentDtos.ActionReq req) {
        return ApiResponse.success("胜者已确认并自动晋级", tournamentService.confirmWinner(matchId, req));
    }
}
