package com.archery.range.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 团体淘汰赛计分台的入参与出参。
 */
public final class TournamentDtos {

    private TournamentDtos() {
    }

    // ---------------- 入参 ----------------

    /** 所有写操作都带操作人角色与姓名，服务端据此鉴权 */
    public record OperatorReq(
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写操作人姓名") String operator) {
    }

    /** 建立赛事：队数 + 每场局数 + 每局每人箭数 */
    public record CreateReq(
            @NotBlank(message = "请填写赛事名称") @Size(max = 64, message = "赛事名称不能超过 64 字") String name,
            @NotNull(message = "请选择 4 或 8 支参赛队") Integer teamSize,
            @NotNull(message = "请设置每场局数") Integer endsPerMatch,
            @NotNull(message = "请设置每名队员每局的规定箭数") Integer arrowsPerEnd,
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    /** 新增 / 调整参赛队：队名 + 固定三名现有会员（站位 0/1/2） */
    public record TeamReq(
            @NotBlank(message = "请填写队名") @Size(max = 48, message = "队名不能超过 48 字") String teamName,
            @NotNull(message = "请选择三名队员") List<Long> memberIds,
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写值班经理姓名") String operator) {
    }

    /** 记一支箭（未确认局，可按 shotIndex 覆盖更正） */
    public record ArrowReq(
            @NotNull(message = "缺少会员") Long memberId,
            @NotNull(message = "缺少箭序") Integer shotIndex,
            @NotBlank(message = "请选择环数") String ring,
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写裁判姓名") String operator) {
    }

    /** 确认一局 / 确认胜者 / 锁定加赛轮 */
    public record ActionReq(
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写裁判姓名") String operator) {
    }

    /** 撤回当前未结束比赛的最后一局：必须填写原因 */
    public record RetractReq(
            @NotBlank(message = "请填写撤回原因") @Size(max = 500, message = "撤回原因不能超过 500 字") String reason,
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写裁判姓名") String operator) {
    }

    /** 加赛箭：某轮某队员射一支 */
    public record ShootoffArrowReq(
            @NotNull(message = "缺少会员") Long memberId,
            @NotBlank(message = "请选择环数") String ring,
            @NotBlank(message = "请选择当前角色") String role,
            @NotBlank(message = "请填写裁判姓名") String operator) {
    }

    // ---------------- 出参 ----------------

    public record MemberView(Long id, String name, String cardNo, String level) {
    }

    public record TeamMemberView(Long memberId, String name, String cardNo, Integer position) {
    }

    public record TeamView(
            Long id,
            String teamName,
            Integer seedNo,
            String seedLabel,
            Integer finalRank,
            List<TeamMemberView> members) {
    }

    public record ArrowView(Long memberId, String memberName, Integer shotIndex, String ring, Integer ringValue) {
    }

    public record EndView(
            Long id,
            Integer endNo,
            Integer homeScore,
            Integer awayScore,
            Integer homeXCount,
            Integer awayXCount,
            String confirmedBy,
            LocalDateTime confirmedAt,
            List<ArrowView> arrows) {
    }

    /** 进行中的下一局：箭可能尚未录齐 */
    public record DraftEndView(
            Integer endNo,
            Integer expectedArrows,
            Integer recordedArrows,
            Integer homeScore,
            Integer awayScore,
            Integer homeXCount,
            Integer awayXCount,
            List<ArrowView> arrows) {
    }

    public record ShootoffArrowView(Long memberId, String memberName, String ring, Integer ringValue) {
    }

    public record ShootoffRoundView(
            Long id,
            Integer roundNo,
            Integer homeScore,
            Integer awayScore,
            Integer homeXCount,
            Integer awayXCount,
            String status,
            String statusName,
            Long winnerTeamId,
            String winnerLabel,
            List<ShootoffArrowView> arrows) {
    }

    /** 对阵树 / 赛事总览里的一场（含晋级来源说明） */
    public record MatchView(
            Long id,
            Integer roundNo,
            String roundName,
            Integer slot,
            Integer matchNo,
            Long homeTeamId,
            String homeTeamName,
            String homeSeedLabel,
            Long awayTeamId,
            String awayTeamName,
            String awaySeedLabel,
            String homeSource,
            String awaySource,
            Long winnerTeamId,
            String status,
            String statusName,
            String stage,
            String stageName,
            Integer homeScore,
            Integer awayScore,
            Integer homeXCount,
            Integer awayXCount,
            Integer confirmedEnds,
            Integer totalEnds,
            Integer shootoffRounds,
            String confirmedBy,
            LocalDateTime confirmedAt,
            boolean readyToPlay,
            boolean currentMatch) {
    }

    public record LogView(
            Long id,
            Long matchId,
            String action,
            String actionName,
            String operator,
            String role,
            String roleName,
            String detail,
            LocalDateTime createdAt) {
    }

    /** 赛事列表行 */
    public record TournamentSummary(
            Long id,
            String name,
            Integer teamSize,
            Integer endsPerMatch,
            Integer arrowsPerEnd,
            String status,
            String statusName,
            Integer currentRound,
            String currentRoundName,
            Integer teamCount,
            Long championTeamId,
            String championTeamName,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }

    /** 赛事总览：队伍 + 全量对阵 + 完整轨迹 */
    public record TournamentDetail(
            TournamentSummary tournament,
            List<TeamView> teams,
            List<MatchView> matches,
            List<LogView> logs) {
    }

    /** 单场计分区：场次 + 双方队员 + 已确认局 + 进行中的下一局 + 加赛轮 */
    public record MatchDetail(
            MatchView match,
            TeamView homeTeam,
            TeamView awayTeam,
            List<EndView> ends,
            DraftEndView draftEnd,
            List<ShootoffRoundView> shootoffRounds) {
    }
}
