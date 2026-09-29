package com.archery.range.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.common.ForbiddenException;
import com.archery.range.domain.MatchArrow;
import com.archery.range.domain.MatchEnd;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.domain.ShootoffArrow;
import com.archery.range.domain.ShootoffRound;
import com.archery.range.domain.Tournament;
import com.archery.range.domain.TournamentLog;
import com.archery.range.domain.TournamentMatch;
import com.archery.range.domain.TournamentTeam;
import com.archery.range.domain.TournamentTeamMember;
import com.archery.range.dto.TournamentDtos;
import com.archery.range.repository.MatchArrowRepository;
import com.archery.range.repository.MatchEndRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.ShootoffArrowRepository;
import com.archery.range.repository.ShootoffRoundRepository;
import com.archery.range.repository.TournamentLogRepository;
import com.archery.range.repository.TournamentMatchRepository;
import com.archery.range.repository.TournamentRepository;
import com.archery.range.repository.TournamentTeamMemberRepository;
import com.archery.range.repository.TournamentTeamRepository;

/**
 * 馆内团体淘汰赛计分台。
 *
 * 关键设计：
 * 1. 对阵由后端生成、后端推进 —— 开赛即一次性写完全部场次，首轮直接放种子队，
 *    后续轮每个槽位用 home/away_from_match_id 指向来源场次；上一场确认胜者后，
 *    在同一事务内把胜者自动带入下一轮对应槽位，前端不拼接任何对阵关系。
 * 2. 记分不可直接改写 —— 局必须三人规定箭数全部录齐才能确认，确认后冻结；
 *    录错只能由裁判撤回「未结束比赛」的最后一局（连同加赛轮一起回退），
 *    撤回人、时间、原因写进 tournament_log。
 * 3. 平分加赛 —— 规定局总分相同进入加赛箭，每轮三名队员各射一支：先比加赛总分、
 *    再比 X 数，仍平则由系统开下一轮，直到产生唯一胜者才允许确认。
 * 4. 角色隔离 —— 值班经理只管赛事与参赛队；裁判才能推进比赛 / 记分 / 撤回 / 确认胜者；
 *    普通会员只读。所有状态落 MySQL，刷新或服务重启后从库里完整恢复。
 * 5. 并发一致 —— 赛事行 / 场次行悲观写锁串行化所有写操作，重复点击与双窗口互不破坏。
 */
@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final TournamentTeamRepository teamRepository;
    private final TournamentTeamMemberRepository teamMemberRepository;
    private final TournamentMatchRepository matchRepository;
    private final MatchEndRepository endRepository;
    private final MatchArrowRepository arrowRepository;
    private final ShootoffRoundRepository shootoffRoundRepository;
    private final ShootoffArrowRepository shootoffArrowRepository;
    private final TournamentLogRepository logRepository;
    private final MemberRepository memberRepository;

    public TournamentService(TournamentRepository tournamentRepository,
            TournamentTeamRepository teamRepository,
            TournamentTeamMemberRepository teamMemberRepository,
            TournamentMatchRepository matchRepository,
            MatchEndRepository endRepository,
            MatchArrowRepository arrowRepository,
            ShootoffRoundRepository shootoffRoundRepository,
            ShootoffArrowRepository shootoffArrowRepository,
            TournamentLogRepository logRepository,
            MemberRepository memberRepository) {
        this.tournamentRepository = tournamentRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.matchRepository = matchRepository;
        this.endRepository = endRepository;
        this.arrowRepository = arrowRepository;
        this.shootoffRoundRepository = shootoffRoundRepository;
        this.shootoffArrowRepository = shootoffArrowRepository;
        this.logRepository = logRepository;
        this.memberRepository = memberRepository;
    }

    // ================= 查询 =================

    @Transactional(readOnly = true)
    public List<TournamentDtos.TournamentSummary> list() {
        return tournamentRepository.findAllByOrderByIdDesc().stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public TournamentDtos.TournamentDetail detail(Long id) {
        Tournament tournament = require(id);
        List<TournamentTeam> teams = teamRepository.findByTournamentIdOrderByIdAsc(id);
        List<TournamentMatch> matches = matchRepository.findByTournamentIdOrderByRoundNoAscSlotAsc(id);
        List<TournamentLog> logs = logRepository.findByTournamentIdOrderByIdAsc(id);

        Map<Long, TournamentTeam> teamMap = teams.stream()
                .collect(Collectors.toMap(TournamentTeam::getId, Function.identity()));
        Map<Long, Integer> matchNoById = matches.stream()
                .collect(Collectors.toMap(TournamentMatch::getId, TournamentMatch::getMatchNo));

        List<TournamentDtos.TeamView> teamViews = teams.stream()
                .map(team -> toTeamView(team, membersOf(team.getId()))).toList();
        List<TournamentDtos.MatchView> matchViews = matches.stream()
                .map(match -> toMatchView(match, teamMap, matchNoById)).toList();
        List<TournamentDtos.LogView> logViews = logs.stream().map(this::toLogView).toList();

        return new TournamentDtos.TournamentDetail(toSummary(tournament), teamViews, matchViews, logViews);
    }

    @Transactional(readOnly = true)
    public TournamentDtos.MatchDetail matchDetail(Long matchId) {
        TournamentMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new BizException("场次不存在：id=" + matchId));
        Tournament tournament = require(match.getTournamentId());
        Map<Long, TournamentTeam> teamMap = teamRepository
                .findByTournamentIdOrderByIdAsc(match.getTournamentId()).stream()
                .collect(Collectors.toMap(TournamentTeam::getId, Function.identity()));
        Map<Long, Integer> matchNoById = matchRepository
                .findByTournamentIdOrderByRoundNoAscSlotAsc(match.getTournamentId()).stream()
                .collect(Collectors.toMap(TournamentMatch::getId, TournamentMatch::getMatchNo));

        TournamentDtos.TeamView home = match.getHomeTeamId() == null ? null
                : toTeamView(teamMap.get(match.getHomeTeamId()), membersOf(match.getHomeTeamId()));
        TournamentDtos.TeamView away = match.getAwayTeamId() == null ? null
                : toTeamView(teamMap.get(match.getAwayTeamId()), membersOf(match.getAwayTeamId()));

        // 已确认局：按局号，附带每支箭（未确认的录入局不进历史明细）
        List<TournamentDtos.EndView> endViews = endRepository
                .findByMatchIdAndStatusOrderByEndNoAsc(matchId, "CONFIRMED").stream()
                .map(end -> toEndView(end, arrowRepository.findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(end.getId())))
                .toList();

        // 进行中的下一局：双方队伍到齐后才允许记分，三名队员的箭可能还没录齐
        TournamentDtos.DraftEndView draftEnd = null;
        long confirmedCount = endRepository.countByMatchIdAndStatus(matchId, "CONFIRMED");
        MatchEnd draftRow = endRepository.findByMatchIdAndStatus(matchId, "DRAFT").orElse(null);
        int nextEndNo = endRepository.findTopByMatchIdOrderByEndNoDesc(matchId)
                .map(MatchEnd::getEndNo).orElse(0) + 1;
        if (!"CONFIRMED".equals(match.getStatus()) && "REGULATION".equals(match.getStage())
                && confirmedCount < tournament.getEndsPerMatch()
                && match.getHomeTeamId() != null && match.getAwayTeamId() != null) {
            List<MatchArrow> draftArrows = draftRow == null ? List.of()
                    : arrowRepository.findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(draftRow.getId());
            int draftNo = draftRow == null ? nextEndNo : draftRow.getEndNo();
            int[] draftSums = sumArrows(draftArrows, match.getHomeTeamId(), match.getAwayTeamId());
            draftEnd = new TournamentDtos.DraftEndView(
                    draftNo,
                    RangeDict.MEMBERS_PER_TEAM * tournament.getArrowsPerEnd(),
                    draftArrows.size(),
                    draftSums[0], draftSums[1],
                    countX(draftArrows, match.getHomeTeamId()),
                    countX(draftArrows, match.getAwayTeamId()),
                    draftArrows.stream().map(a -> toArrowView(a, nameOf(a.getMemberId()))).toList());
        }

        List<TournamentDtos.ShootoffRoundView> shootoffViews = new ArrayList<>();
        Map<Long, String> teamNameMap = teamMap.values().stream()
                .collect(Collectors.toMap(TournamentTeam::getId, TournamentTeam::getTeamName));
        for (ShootoffRound round : shootoffRoundRepository.findByMatchIdOrderByRoundNoAsc(matchId)) {
            List<ShootoffArrow> arrows = shootoffArrowRepository.findByRoundIdOrderByTeamIdAscMemberIdAsc(round.getId());
            String winnerLabel = round.getWinnerTeamId() == null ? null
                    : teamNameMap.getOrDefault(round.getWinnerTeamId(), "");
            shootoffViews.add(new TournamentDtos.ShootoffRoundView(
                    round.getId(),
                    round.getRoundNo(),
                    round.getHomeScore(),
                    round.getAwayScore(),
                    round.getHomeXCount(),
                    round.getAwayXCount(),
                    round.getStatus(),
                    "DRAFT".equals(round.getStatus()) ? "录入中" : "已锁定",
                    round.getWinnerTeamId(),
                    winnerLabel,
                    arrows.stream().map(a -> new TournamentDtos.ShootoffArrowView(
                            a.getMemberId(), nameOf(a.getMemberId()), a.getRing(), a.getRingValue())).toList()));
        }

        return new TournamentDtos.MatchDetail(
                toMatchView(match, teamMap, matchNoById),
                home, away, endViews, draftEnd, shootoffViews);
    }

    // ================= 赛事与参赛队（值班经理） =================

    @Transactional
    public TournamentDtos.TournamentDetail create(TournamentDtos.CreateReq req) {
        requireManagerRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "值班经理");
        if (!RangeDict.isValidTeamSize(req.teamSize())) {
            throw new BizException("参赛队数只能是 4 支或 8 支");
        }
        if (!RangeDict.isValidEndsPerMatch(req.endsPerMatch())) {
            throw new BizException("每场局数只能是 1~6 局");
        }
        if (!RangeDict.isValidArrowsPerEnd(req.arrowsPerEnd())) {
            throw new BizException("每名队员每局规定箭数只能是 1~3 支");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new BizException("请填写赛事名称");
        }

        Tournament tournament = new Tournament();
        tournament.setName(name);
        tournament.setTeamSize(req.teamSize());
        tournament.setEndsPerMatch(req.endsPerMatch());
        tournament.setArrowsPerEnd(req.arrowsPerEnd());
        tournament.setStatus("DRAFT");
        tournament.setCreatedBy(operator);
        tournament.setCreatedAt(LocalDateTime.now());
        tournament = tournamentRepository.save(tournament);
        log(tournament.getId(), null, "CREATE", operator, "MANAGER",
                "建立赛事：" + name + "；" + req.teamSize() + " 支队、每场 " + req.endsPerMatch()
                        + " 局、每名队员每局 " + req.arrowsPerEnd() + " 支箭");
        return detail(tournament.getId());
    }

    @Transactional
    public TournamentDtos.TournamentDetail addTeam(Long tournamentId, TournamentDtos.TeamReq req) {
        Tournament tournament = lockTournament(tournamentId);
        requireManagerRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "值班经理");
        requireDraft(tournament);

        List<TournamentTeam> teams = teamRepository.findByTournamentIdOrderByIdAsc(tournamentId);
        if (teams.size() >= tournament.getTeamSize()) {
            throw new BizException("本场赛事最多 " + tournament.getTeamSize() + " 支队，队伍已报满");
        }
        String teamName = normalizeTeamName(req.teamName());
        if (teams.stream().anyMatch(team -> team.getTeamName().equals(teamName))) {
            throw new BizException("队名「" + teamName + "」已存在，请换一个队名");
        }
        List<Long> memberIds = normalizeMembers(req.memberIds());
        ensureMembersFree(tournamentId, memberIds, null);
        Member[] members = resolveMembers(memberIds);

        TournamentTeam team = new TournamentTeam();
        team.setTournamentId(tournamentId);
        team.setTeamName(teamName);
        team.setCreatedAt(LocalDateTime.now());
        team = teamRepository.save(team);
        bindMembers(team, memberIds, members);
        log(tournamentId, null, "ADD_TEAM", operator, "MANAGER",
                "新增参赛队「" + teamName + "」：" + memberNames(members));
        return detail(tournamentId);
    }

    @Transactional
    public TournamentDtos.TournamentDetail updateTeam(Long tournamentId, Long teamId,
            TournamentDtos.TeamReq req) {
        Tournament tournament = lockTournament(tournamentId);
        requireManagerRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "值班经理");
        requireDraft(tournament);

        TournamentTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BizException("参赛队不存在：id=" + teamId));
        if (!team.getTournamentId().equals(tournamentId)) {
            throw new BizException("该队伍不属于本场赛事");
        }
        String teamName = normalizeTeamName(req.teamName());
        List<TournamentTeam> teams = teamRepository.findByTournamentIdOrderByIdAsc(tournamentId);
        if (teams.stream().anyMatch(other -> !other.getId().equals(teamId) && other.getTeamName().equals(teamName))) {
            throw new BizException("队名「" + teamName + "」已存在，请换一个队名");
        }
        List<Long> memberIds = normalizeMembers(req.memberIds());
        ensureMembersFree(tournamentId, memberIds, teamId);
        Member[] members = resolveMembers(memberIds);

        team.setTeamName(teamName);
        teamRepository.save(team);
        teamMemberRepository.deleteByTeamId(teamId);
        teamMemberRepository.flush();
        bindMembers(team, memberIds, members);
        log(tournamentId, null, "UPDATE_TEAM", operator, "MANAGER",
                "调整参赛队「" + teamName + "」为：" + memberNames(members));
        return detail(tournamentId);
    }

    @Transactional
    public TournamentDtos.TournamentDetail removeTeam(Long tournamentId, Long teamId,
            TournamentDtos.OperatorReq req) {
        Tournament tournament = lockTournament(tournamentId);
        requireManagerRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "值班经理");
        requireDraft(tournament);

        TournamentTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BizException("参赛队不存在：id=" + teamId));
        if (!team.getTournamentId().equals(tournamentId)) {
            throw new BizException("该队伍不属于本场赛事");
        }
        teamMemberRepository.deleteByTeamId(teamId);
        teamMemberRepository.flush();
        teamRepository.delete(team);
        log(tournamentId, null, "REMOVE_TEAM", operator, "MANAGER", "移除参赛队「" + team.getTeamName() + "」");
        return detail(tournamentId);
    }

    /**
     * 开赛：校验队伍数量与每队三人，固定种子位，由后端一次性生成完整淘汰对阵并冻结。
     */
    @Transactional
    public TournamentDtos.TournamentDetail start(Long tournamentId, TournamentDtos.OperatorReq req) {
        Tournament tournament = lockTournament(tournamentId);
        requireManagerRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "值班经理");
        requireDraft(tournament);

        List<TournamentTeam> teams = teamRepository.findByTournamentIdOrderByIdAsc(tournamentId);
        if (teams.size() != tournament.getTeamSize()) {
            throw new BizException("需要报满 " + tournament.getTeamSize() + " 支队才能开赛，当前仅 "
                    + teams.size() + " 支");
        }
        for (TournamentTeam team : teams) {
            if (teamMemberRepository.countByTeamId(team.getId()) != RangeDict.MEMBERS_PER_TEAM) {
                throw new BizException("队伍「" + team.getTeamName() + "」必须固定三名会员才能开赛");
            }
        }

        // 固定种子位（按建队顺序），开赛后队伍与对阵都不能再替换
        for (int index = 0; index < teams.size(); index += 1) {
            TournamentTeam team = teams.get(index);
            team.setSeedNo(index + 1);
            teamRepository.save(team);
        }
        generateBracket(tournament, teams);

        tournament.setStatus("ONGOING");
        tournament.setCurrentRound(1);
        tournament.setStartedAt(LocalDateTime.now());
        tournamentRepository.save(tournament);
        log(tournamentId, null, "START", operator, "MANAGER",
                "开赛：生成 " + tournament.getTeamSize() + " 队淘汰对阵，队伍与对阵关系冻结");
        return detail(tournamentId);
    }

    /**
     * 生成单淘汰对阵：
     * 首轮按标准种子排位（1v8、4v5、3v6、2v7 / 4 队为 1v4、3v2），
     * 之后每一轮相邻两场的胜者组成下一场的主/客队，晋级来源场次全程落库。
     */
    private void generateBracket(Tournament tournament, List<TournamentTeam> teams) {
        int size = tournament.getTeamSize();
        int totalRounds = size == 4 ? 2 : 3;
        Map<Integer, TournamentTeam> bySeed = teams.stream()
                .collect(Collectors.toMap(TournamentTeam::getSeedNo, Function.identity()));

        // 本轮场次（先放首轮），逐轮生成下一轮
        List<TournamentMatch> currentRoundMatches = new ArrayList<>();
        int[] firstSeeds = size == 4 ? new int[] {1, 4, 3, 2}
                : new int[] {1, 8, 4, 5, 3, 6, 2, 7};
        int matchNo = 1;
        for (int pair = 0; pair < size / 2; pair += 1) {
            TournamentMatch match = newMatch(tournament, 1, pair + 1, matchNo++);
            match.setHomeTeamId(bySeed.get(firstSeeds[pair * 2]).getId());
            match.setAwayTeamId(bySeed.get(firstSeeds[pair * 2 + 1]).getId());
            currentRoundMatches.add(matchRepository.save(match));
        }

        for (int round = 2; round <= totalRounds; round += 1) {
            List<TournamentMatch> nextRoundMatches = new ArrayList<>();
            int slots = currentRoundMatches.size() / 2;
            for (int slot = 1; slot <= slots; slot += 1) {
                TournamentMatch homeSource = currentRoundMatches.get((slot - 1) * 2);
                TournamentMatch awaySource = currentRoundMatches.get((slot - 1) * 2 + 1);
                TournamentMatch match = newMatch(tournament, round, slot, matchNo++);
                match.setHomeFromMatchId(homeSource.getId());
                match.setAwayFromMatchId(awaySource.getId());
                nextRoundMatches.add(matchRepository.save(match));
            }
            currentRoundMatches = nextRoundMatches;
        }
    }

    private TournamentMatch newMatch(Tournament tournament, int roundNo, int slot, int matchNo) {
        TournamentMatch match = new TournamentMatch();
        match.setTournamentId(tournament.getId());
        match.setRoundNo(roundNo);
        match.setSlot(slot);
        match.setMatchNo(matchNo);
        match.setStatus("PENDING");
        match.setStage("REGULATION");
        match.setHomeScore(0);
        match.setAwayScore(0);
        match.setHomeXCount(0);
        match.setAwayXCount(0);
        match.setShootoffRoundCount(0);
        return match;
    }

    // ================= 裁判记分 =================

    /**
     * 记一支局内箭（未确认局，可按 shotIndex 覆盖更正）。
     * 校验：裁判角色、比赛未确认、规定局阶段、会员必须属于本场两支队伍之一。
     */
    @Transactional
    public TournamentDtos.MatchDetail recordArrow(Long matchId, TournamentDtos.ArrowReq req) {
        TournamentMatch match = lockMatch(matchId);
        requireRefereeRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "裁判");
        rejectConfirmedMatch(match);
        if (!"REGULATION".equals(match.getStage())) {
            throw new BizException("本场已进入加赛箭，请在加赛区录箭");
        }
        Tournament tournament = require(match.getTournamentId());
        Long teamId = requireMemberInMatch(match, req.memberId());

        String ring = normalizeRing(req.ring());
        int shotIndex = req.shotIndex() == null ? -1 : req.shotIndex();
        if (shotIndex < 0 || shotIndex >= tournament.getArrowsPerEnd()) {
            throw new BizException("箭序超出每名队员每局 " + tournament.getArrowsPerEnd() + " 支的规定箭数");
        }
        long confirmed = endRepository.countByMatchIdAndStatus(matchId, "CONFIRMED");
        if (confirmed >= tournament.getEndsPerMatch()) {
            throw new BizException("规定局数已全部结束，不能再记局内箭");
        }
        // 一场只允许一个录入中的局：优先复用它；没有才按「最大局号 + 1」开新局
        MatchEnd end = endRepository.findByMatchIdAndStatus(matchId, "DRAFT").orElse(null);
        if (end == null) {
            int endNo = endRepository.findTopByMatchIdOrderByEndNoDesc(matchId)
                    .map(MatchEnd::getEndNo).orElse(0) + 1;
            end = new MatchEnd();
            end.setMatchId(matchId);
            end.setEndNo(endNo);
            end.setHomeScore(0);
            end.setAwayScore(0);
            end.setHomeXCount(0);
            end.setAwayXCount(0);
            end.setStatus("DRAFT");
            end.setConfirmedBy(operator);
            end.setConfirmedAt(LocalDateTime.now());
            endRepository.save(end);
            // 关键：同事务后续要按 DRAFT 查回这一局，先 flush 让新局对查询可见。
            endRepository.flush();
        }

        MatchArrow arrow = arrowRepository
                .findByEndIdAndMemberIdAndShotIndex(end.getId(), req.memberId(), shotIndex)
                .orElseGet(MatchArrow::new);
        arrow.setEndId(end.getId());
        arrow.setMatchId(matchId);
        arrow.setTeamId(teamId);
        arrow.setMemberId(req.memberId());
        arrow.setShotIndex(shotIndex);
        arrow.setRing(ring);
        arrow.setRingValue(RangeDict.ringValue(ring));
        arrowRepository.save(arrow);

        // 未确认局允许就地覆盖，实时汇总本局分，供计分台展示
        List<MatchArrow> endArrows = arrowRepository.findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(end.getId());
        int[] endSums = sumArrows(endArrows, match.getHomeTeamId(), match.getAwayTeamId());
        end.setHomeScore(endSums[0]);
        end.setAwayScore(endSums[1]);
        end.setHomeXCount(countX(endArrows, match.getHomeTeamId()));
        end.setAwayXCount(countX(endArrows, match.getAwayTeamId()));
        endRepository.save(end);

        if ("PENDING".equals(match.getStatus())) {
            match.setStatus("ONGOING");
            matchRepository.save(match);
        }
        return matchDetail(matchId);
    }

    /**
     * 确认一局：三名队员规定箭数全部录齐才能确认，确认后冻结。
     * 最后一局确认后：总分有高低 → 待确认胜者；总分相同 → 进入加赛箭。
     */
    @Transactional
    public TournamentDtos.MatchDetail confirmEnd(Long matchId, TournamentDtos.ActionReq req) {
        TournamentMatch match = lockMatch(matchId);
        requireRefereeRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "裁判");
        rejectConfirmedMatch(match);
        if (!"REGULATION".equals(match.getStage())) {
            throw new BizException("本场已进入加赛箭，请在加赛区操作");
        }
        Tournament tournament = require(match.getTournamentId());
        long confirmed = endRepository.countByMatchIdAndStatus(matchId, "CONFIRMED");
        if (confirmed >= tournament.getEndsPerMatch()) {
            throw new BizException("规定局数已全部确认");
        }
        MatchEnd end = endRepository.findByMatchIdAndStatus(matchId, "DRAFT")
                .orElseThrow(() -> new BizException("当前局还没有录箭，不能确认"));
        List<MatchArrow> arrows = arrowRepository.findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(end.getId());
        int required = RangeDict.MEMBERS_PER_TEAM * tournament.getArrowsPerEnd();
        if (arrows.size() < required * 2) {
            throw new BizException("箭数未齐：本局每队需三名队员各射 " + tournament.getArrowsPerEnd()
                    + " 支（共 " + (required * 2) + " 支），当前仅 " + arrows.size() + " 支，不能确认");
        }
        // 必须两名队伍的三名队员都录满
        ensureEndComplete(match, arrows, tournament.getArrowsPerEnd());

        end.setStatus("CONFIRMED");
        end.setConfirmedBy(operator);
        end.setConfirmedAt(LocalDateTime.now());
        endRepository.save(end);
        refreshRegulationTotals(match);
        log(match.getTournamentId(), matchId, "CONFIRM_END", operator, "REFEREE",
                "第 " + end.getEndNo() + " 局确认：主队 " + end.getHomeScore() + "、客队 " + end.getAwayScore()
                        + "；累计 " + match.getHomeScore() + ":" + match.getAwayScore());

        long confirmedAfter = endRepository.countByMatchIdAndStatus(matchId, "CONFIRMED");
        if (confirmedAfter >= tournament.getEndsPerMatch()) {
            if (match.getHomeScore().equals(match.getAwayScore())) {
                // 平分：进入加赛箭，开第一轮 DRAFT
                match.setStage("SHOOTOFF");
                match.setStatus("ONGOING");
                match.setShootoffRoundCount(1);
                matchRepository.save(match);
                openShootoffRound(match, 1);
                log(match.getTournamentId(), matchId, "ENTER_SHOOTOFF", "计分系统", "SYSTEM",
                        "规定 " + tournament.getEndsPerMatch() + " 局结束，双方 "
                                + match.getHomeScore() + " 平，进入加赛箭（每轮每名队员各射一支）");
            } else {
                match.setStatus("AWAIT_CONFIRM");
                matchRepository.save(match);
            }
        } else if ("PENDING".equals(match.getStatus())) {
            match.setStatus("ONGOING");
            matchRepository.save(match);
        }
        return matchDetail(matchId);
    }

    /**
     * 撤回当前未结束比赛的最后一局。
     * 若已进入加赛箭，先清掉所有加赛轮回到规定局；确认后的局不能跨局改写，
     * 撤回人、时间、原因全部写入轨迹。
     */
    @Transactional
    public TournamentDtos.MatchDetail retractLastEnd(Long matchId, TournamentDtos.RetractReq req) {
        TournamentMatch match = lockMatch(matchId);
        requireRefereeRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "裁判");
        if ("CONFIRMED".equals(match.getStatus())) {
            throw new BizException("比赛已确认不能改分：胜者已锁定，不能撤回局成绩");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("撤回最后一局必须填写原因");
        }

        // 已开加赛：连同加赛轮一起回退到规定局
        if ("SHOOTOFF".equals(match.getStage())) {
            for (ShootoffRound round : shootoffRoundRepository.findByMatchIdOrderByRoundNoAsc(matchId)) {
                shootoffArrowRepository.deleteByRoundId(round.getId());
                shootoffArrowRepository.flush();
                shootoffRoundRepository.delete(round);
            }
            match.setStage("REGULATION");
            match.setShootoffRoundCount(0);
        }

        // 撤回最后一局：优先撤回录入中的局；没有录入局才撤回已确认的最后一局
        MatchEnd last = endRepository.findByMatchIdAndStatus(matchId, "DRAFT").orElse(null);
        if (last == null) {
            List<MatchEnd> confirmedEnds = endRepository.findByMatchIdAndStatusOrderByEndNoAsc(matchId, "CONFIRMED");
            if (!confirmedEnds.isEmpty()) {
                last = confirmedEnds.get(confirmedEnds.size() - 1);
            }
        }
        if (last == null) {
            throw new BizException("当前没有已记录的局，无需撤回");
        }
        List<MatchArrow> removed = arrowRepository
                .findByEndIdOrderByTeamIdAscMemberIdAscShotIndexAsc(last.getId());
        int removedArrows = removed.size();
        arrowRepository.deleteByEndId(last.getId());
        arrowRepository.flush();
        endRepository.delete(last);
        refreshRegulationTotals(match);
        match.setStatus("ONGOING");
        matchRepository.save(match);

        log(match.getTournamentId(), matchId, "RETRACT_END", operator, "REFEREE",
                "撤回第 " + last.getEndNo() + " 局（"
                        + ("DRAFT".equals(last.getStatus()) ? "录入中" : "已确认")
                        + "，清除 " + removedArrows + " 支箭），原因：" + reason);
        return matchDetail(matchId);
    }

    /**
     * 记一支加赛箭（当前 DRAFT 轮，每名队员仅一支，可覆盖）。
     */
    @Transactional
    public TournamentDtos.MatchDetail recordShootoffArrow(Long matchId,
            TournamentDtos.ShootoffArrowReq req) {
        TournamentMatch match = lockMatch(matchId);
        requireRefereeRole(req.role(), req.operator());
        requireName(req.operator(), "裁判");
        rejectConfirmedMatch(match);
        if (!"SHOOTOFF".equals(match.getStage())) {
            throw new BizException("本场尚未进入加赛箭");
        }
        Long teamId = requireMemberInMatch(match, req.memberId());
        String ring = normalizeRing(req.ring());

        List<ShootoffRound> rounds = shootoffRoundRepository.findByMatchIdOrderByRoundNoAsc(matchId);
        ShootoffRound active = rounds.stream()
                .filter(round -> "DRAFT".equals(round.getStatus()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new BizException("没有录入中的加赛轮"));

        ShootoffArrow arrow = shootoffArrowRepository
                .findByRoundIdAndMemberId(active.getId(), req.memberId())
                .orElseGet(ShootoffArrow::new);
        arrow.setRoundId(active.getId());
        arrow.setMatchId(matchId);
        arrow.setTeamId(teamId);
        arrow.setMemberId(req.memberId());
        arrow.setRing(ring);
        arrow.setRingValue(RangeDict.ringValue(ring));
        shootoffArrowRepository.save(arrow);

        List<ShootoffArrow> arrows =
                shootoffArrowRepository.findByRoundIdOrderByTeamIdAscMemberIdAsc(active.getId());
        int[] sums = sumShootoffArrows(arrows, match.getHomeTeamId(), match.getAwayTeamId());
        active.setHomeScore(sums[0]);
        active.setAwayScore(sums[1]);
        active.setHomeXCount(countShootoffX(arrows, match.getHomeTeamId()));
        active.setAwayXCount(countShootoffX(arrows, match.getAwayTeamId()));
        shootoffRoundRepository.save(active);

        if ("PENDING".equals(match.getStatus())) {
            match.setStatus("ONGOING");
            matchRepository.save(match);
        }
        return matchDetail(matchId);
    }

    /**
     * 锁定当前加赛轮：六支箭录齐后由裁判锁定。
     * 先比加赛总分、再比 X 数；分出胜负 → 待确认胜者；仍平 → 系统开下一轮。
     */
    @Transactional
    public TournamentDtos.MatchDetail lockShootoffRound(Long matchId, TournamentDtos.ActionReq req) {
        TournamentMatch match = lockMatch(matchId);
        requireRefereeRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "裁判");
        rejectConfirmedMatch(match);
        if (!"SHOOTOFF".equals(match.getStage())) {
            throw new BizException("本场尚未进入加赛箭");
        }
        List<ShootoffRound> rounds = shootoffRoundRepository.findByMatchIdOrderByRoundNoAsc(matchId);
        ShootoffRound active = rounds.stream()
                .filter(round -> "DRAFT".equals(round.getStatus()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new BizException("没有录入中的加赛轮"));
        List<ShootoffArrow> arrows =
                shootoffArrowRepository.findByRoundIdOrderByTeamIdAscMemberIdAsc(active.getId());
        if (arrows.size() < RangeDict.MEMBERS_PER_TEAM * 2) {
            throw new BizException("箭数未齐：加赛轮每队需三名队员各射一支（共 "
                    + (RangeDict.MEMBERS_PER_TEAM * 2) + " 支），当前仅 " + arrows.size() + " 支，不能锁定");
        }
        ensureShootoffComplete(match, arrows);

        LocalDateTime now = LocalDateTime.now();
        active.setStatus("LOCKED");
        active.setLockedBy(operator);
        active.setLockedAt(now);

        Long winner = decideShootoffWinner(match, active);
        if (winner != null) {
            active.setWinnerTeamId(winner);
            match.setStatus("AWAIT_CONFIRM");
            log(match.getTournamentId(), matchId, "LOCK_SHOOTOFF", operator, "REFEREE",
                    "第 " + active.getRoundNo() + " 轮加赛锁定：" + active.getHomeScore() + ":"
                            + active.getAwayScore() + "（X " + active.getHomeXCount() + ":"
                            + active.getAwayXCount() + "），已分出胜负");
        } else {
            int nextNo = active.getRoundNo() + 1;
            match.setShootoffRoundCount(nextNo);
            log(match.getTournamentId(), matchId, "LOCK_SHOOTOFF", operator, "REFEREE",
                    "第 " + active.getRoundNo() + " 轮加赛锁定：" + active.getHomeScore() + ":"
                            + active.getAwayScore() + "（X " + active.getHomeXCount() + ":"
                            + active.getAwayXCount() + "），仍平分");
            log(match.getTournamentId(), matchId, "CONTINUE_SHOOTOFF", "计分系统", "SYSTEM",
                    "加赛总分与 X 数均相同，继续进行第 " + nextNo + " 轮加赛，直到产生唯一胜者");
        }
        shootoffRoundRepository.save(active);
        matchRepository.save(match);
        if (winner == null) {
            openShootoffRound(match, active.getRoundNo() + 1);
        }
        return matchDetail(matchId);
    }

    /**
     * 裁判确认胜者（规定局或加赛分出胜负后）。已确认不可倒退；
     * 同一事务内把胜者自动带入下一轮对应槽位，并推进赛事当前轮次 / 完赛状态。
     */
    @Transactional
    public TournamentDtos.MatchDetail confirmWinner(Long matchId, TournamentDtos.ActionReq req) {
        TournamentMatch match = lockMatch(matchId);
        requireRefereeRole(req.role(), req.operator());
        String operator = requireName(req.operator(), "裁判");
        if ("CONFIRMED".equals(match.getStatus())) {
            throw new BizException("比赛已确认不能改分：本场胜者已锁定");
        }
        Long winnerId;
        String how;
        if ("REGULATION".equals(match.getStage())) {
            if (!"AWAIT_CONFIRM".equals(match.getStatus())) {
                throw new BizException("规定局数尚未结束，不能确认胜者");
            }
            if (match.getHomeScore().equals(match.getAwayScore())) {
                throw new BizException("双方总分相同，须进入加赛箭直到产生唯一胜者");
            }
            winnerId = match.getHomeScore() > match.getAwayScore()
                    ? match.getHomeTeamId() : match.getAwayTeamId();
            how = "规定局总分 " + match.getHomeScore() + ":" + match.getAwayScore();
        } else {
            if (!"AWAIT_CONFIRM".equals(match.getStatus())) {
                throw new BizException("加赛尚未分出唯一胜者，不能确认；仍平请继续下一轮加赛");
            }
            List<ShootoffRound> rounds = shootoffRoundRepository.findByMatchIdOrderByRoundNoAsc(matchId);
            ShootoffRound decisive = rounds.stream()
                    .filter(round -> round.getWinnerTeamId() != null)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new BizException("加赛尚未分出唯一胜者，不能确认"));
            winnerId = decisive.getWinnerTeamId();
            how = "第 " + decisive.getRoundNo() + " 轮加赛 " + decisive.getHomeScore() + ":"
                    + decisive.getAwayScore() + "（X " + decisive.getHomeXCount() + ":"
                    + decisive.getAwayXCount() + "）";
        }

        Long loserId = winnerId.equals(match.getHomeTeamId()) ? match.getAwayTeamId() : match.getHomeTeamId();
        match.setWinnerTeamId(winnerId);
        match.setStatus("CONFIRMED");
        match.setConfirmedBy(operator);
        match.setConfirmedAt(LocalDateTime.now());
        matchRepository.save(match);
        TournamentTeam winner = teamRepository.findById(winnerId).orElse(null);
        String winnerName = winner == null ? "" : winner.getTeamName();
        log(match.getTournamentId(), matchId, "CONFIRM_WINNER", operator, "REFEREE",
                "确认「" + winnerName + "」晋级（" + how + "）");

        // 自动带入下一轮槽位（不靠前端拼接）
        Tournament tournament = lockTournament(match.getTournamentId());
        advanceFeeder(match, winnerId, winnerName);
        advanceTournamentState(tournament);
        if (loserId != null) {
            // 决赛负者亚军
            boolean isFinal = matchRepository.findByTournamentIdOrderByRoundNoAscSlotAsc(tournament.getId())
                    .stream().noneMatch(m -> m.getRoundNo() > match.getRoundNo());
            if (isFinal) {
                teamRepository.findById(loserId).ifPresent(loser -> {
                    loser.setFinalRank(2);
                    teamRepository.save(loser);
                });
                winner.setFinalRank(1);
                teamRepository.save(winner);
            }
        }
        return matchDetail(matchId);
    }

    /** 把已确认场次的胜者写入下一轮的主/客队槽位，并留自动晋级轨迹 */
    private void advanceFeeder(TournamentMatch confirmed, Long winnerId, String winnerName) {
        List<TournamentMatch> next = matchRepository
                .findByHomeFromMatchIdOrAwayFromMatchId(confirmed.getId(), confirmed.getId());
        for (TournamentMatch target : next) {
            String side;
            if (confirmed.getId().equals(target.getHomeFromMatchId())) {
                if (target.getHomeTeamId() == null) {
                    target.setHomeTeamId(winnerId);
                }
                side = "主队";
            } else {
                if (target.getAwayTeamId() == null) {
                    target.setAwayTeamId(winnerId);
                }
                side = "客队";
            }
            matchRepository.save(target);
            log(confirmed.getTournamentId(), target.getId(), "ADVANCE_AUTO", "计分系统", "SYSTEM",
                    "第 " + confirmed.getMatchNo() + " 场胜者「" + winnerName + "」自动带入第 "
                            + target.getMatchNo() + " 场" + side + "槽位");
        }
    }

    /** 重算赛事当前轮次；全部确认则完赛并记录冠军 */
    private void advanceTournamentState(Tournament tournament) {
        List<TournamentMatch> all = matchRepository
                .findByTournamentIdOrderByRoundNoAscSlotAsc(tournament.getId());
        boolean allConfirmed = all.stream().allMatch(match -> "CONFIRMED".equals(match.getStatus()));
        if (allConfirmed) {
            TournamentMatch finalMatch = all.get(all.size() - 1);
            tournament.setStatus("FINISHED");
            tournament.setCurrentRound(null);
            tournament.setChampionTeamId(finalMatch.getWinnerTeamId());
            tournament.setFinishedAt(LocalDateTime.now());
            tournamentRepository.save(tournament);
            String championName = teamRepository.findById(finalMatch.getWinnerTeamId())
                    .map(TournamentTeam::getTeamName).orElse("");
            log(tournament.getId(), finalMatch.getId(), "CONFIRM_WINNER", "计分系统", "SYSTEM",
                    "全部场次结束，「" + championName + "」获得赛事冠军");
        } else {
            int current = all.stream()
                    .filter(match -> !"CONFIRMED".equals(match.getStatus()))
                    .mapToInt(TournamentMatch::getRoundNo)
                    .min().orElse(tournament.getTeamSize() == 4 ? 2 : 3);
            tournament.setCurrentRound(current);
            tournamentRepository.save(tournament);
        }
    }

    // ================= 内部工具 =================

    private void openShootoffRound(TournamentMatch match, int roundNo) {
        ShootoffRound round = new ShootoffRound();
        round.setMatchId(match.getId());
        round.setRoundNo(roundNo);
        round.setHomeScore(0);
        round.setAwayScore(0);
        round.setHomeXCount(0);
        round.setAwayXCount(0);
        round.setStatus("DRAFT");
        shootoffRoundRepository.save(round);
    }

    private Long decideShootoffWinner(TournamentMatch match, ShootoffRound round) {
        if (!round.getHomeScore().equals(round.getAwayScore())) {
            return round.getHomeScore() > round.getAwayScore()
                    ? match.getHomeTeamId() : match.getAwayTeamId();
        }
        if (!round.getHomeXCount().equals(round.getAwayXCount())) {
            return round.getHomeXCount() > round.getAwayXCount()
                    ? match.getHomeTeamId() : match.getAwayTeamId();
        }
        return null;
    }

    /** 已确认局的总分 / X 数重算到场次行（录入中的局不计入） */
    private void refreshRegulationTotals(TournamentMatch match) {
        int home = 0;
        int away = 0;
        int homeX = 0;
        int awayX = 0;
        for (MatchEnd end : endRepository.findByMatchIdAndStatusOrderByEndNoAsc(match.getId(), "CONFIRMED")) {
            home += end.getHomeScore();
            away += end.getAwayScore();
            homeX += end.getHomeXCount();
            awayX += end.getAwayXCount();
        }
        match.setHomeScore(home);
        match.setAwayScore(away);
        match.setHomeXCount(homeX);
        match.setAwayXCount(awayX);
        matchRepository.save(match);
    }

    /** 校验一局中每队三名队员都录满规定箭数（防止只录部分队员就确认） */
    private void ensureEndComplete(TournamentMatch match, List<MatchArrow> arrows, int arrowsPerEnd) {
        Map<Long, Map<Long, Long>> byTeamMember = arrows.stream().collect(Collectors.groupingBy(
                MatchArrow::getTeamId,
                Collectors.groupingBy(MatchArrow::getMemberId, Collectors.counting())));
        for (Long teamId : List.of(match.getHomeTeamId(), match.getAwayTeamId())) {
            Map<Long, Long> memberCounts = byTeamMember.getOrDefault(teamId, Map.of());
            if (memberCounts.size() != RangeDict.MEMBERS_PER_TEAM) {
                throw new BizException("箭数未齐：队伍必须三名队员各射 " + arrowsPerEnd + " 支，不能确认");
            }
            for (long count : memberCounts.values()) {
                if (count != arrowsPerEnd) {
                    throw new BizException("箭数未齐：每名队员必须射满 " + arrowsPerEnd + " 支，不能确认");
                }
            }
        }
    }

    private void ensureShootoffComplete(TournamentMatch match, List<ShootoffArrow> arrows) {
        Map<Long, Long> byTeam = arrows.stream()
                .collect(Collectors.groupingBy(ShootoffArrow::getTeamId, Collectors.counting()));
        for (Long teamId : List.of(match.getHomeTeamId(), match.getAwayTeamId())) {
            if (byTeam.getOrDefault(teamId, 0L) != RangeDict.MEMBERS_PER_TEAM) {
                throw new BizException("箭数未齐：加赛轮每队三名队员必须各射一支，不能锁定");
            }
        }
    }

    /** 会员必须属于本场两支队伍之一，否则不允许记入成绩 */
    private Long requireMemberInMatch(TournamentMatch match, Long memberId) {
        if (memberId == null) {
            throw new BizException("请选择记箭会员");
        }
        if (match.getHomeTeamId() == null || match.getAwayTeamId() == null) {
            throw new BizException("双方队伍尚未齐（等待晋级来源），暂不能记分");
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BizException("会员不存在：id=" + memberId));
        List<TournamentTeamMember> binds = teamMemberRepository
                .findByTournamentIdOrderByTeamIdAscPositionAsc(match.getTournamentId());
        return binds.stream()
                .filter(bind -> bind.getMemberId().equals(memberId)
                        && (bind.getTeamId().equals(match.getHomeTeamId())
                                || bind.getTeamId().equals(match.getAwayTeamId())))
                .findFirst()
                .map(TournamentTeamMember::getTeamId)
                .orElseThrow(() -> new BizException("无权记分：「" + member.getName()
                        + "」不属于本场两支队伍，不能记入成绩"));
    }

    private void rejectConfirmedMatch(TournamentMatch match) {
        if ("CONFIRMED".equals(match.getStatus())) {
            throw new BizException("比赛已确认不能改分：本场成绩已锁定");
        }
    }

    private void requireDraft(Tournament tournament) {
        if (!"DRAFT".equals(tournament.getStatus())) {
            throw new BizException("赛事已开始不能换队：开赛后队伍与对阵关系已冻结");
        }
    }

    /** 值班经理权限：只有 MANAGER 可管理赛事与参赛队 */
    private void requireManagerRole(String role, String operator) {
        if (!"MANAGER".equals(safe(role))) {
            throw new ForbiddenException("无权操作：只有值班经理可以管理赛事与参赛队");
        }
        if (safe(operator).isBlank()) {
            throw new BizException("请填写值班经理姓名");
        }
    }

    /** 裁判权限：只有 REFEREE 可推进比赛、记分、撤回与确认胜者 */
    private void requireRefereeRole(String role, String operator) {
        if (!"REFEREE".equals(safe(role))) {
            throw new ForbiddenException("无权操作：只有当值裁判可以记分、撤回最后一局与确认胜者");
        }
        if (safe(operator).isBlank()) {
            throw new BizException("请填写裁判姓名");
        }
    }

    private String requireName(String name, String who) {
        String trimmed = safe(name);
        if (trimmed.isBlank()) {
            throw new BizException("请填写" + who + "姓名");
        }
        if (trimmed.length() > 32) {
            throw new BizException(who + "姓名过长");
        }
        return trimmed;
    }

    private String normalizeTeamName(String name) {
        String trimmed = safe(name);
        if (trimmed.isBlank()) {
            throw new BizException("请填写队名");
        }
        return trimmed;
    }

    /** 恰好三名、互不重复的现有会员 */
    private List<Long> normalizeMembers(List<Long> memberIds) {
        if (memberIds == null || memberIds.size() != RangeDict.MEMBERS_PER_TEAM) {
            throw new BizException("每队必须固定三名会员");
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(memberIds));
        if (distinct.size() != RangeDict.MEMBERS_PER_TEAM) {
            throw new BizException("三名队员不能重复");
        }
        if (distinct.stream().anyMatch(id -> id == null)) {
            throw new BizException("请选择三名现有会员");
        }
        return distinct;
    }

    private void ensureMembersFree(Long tournamentId, List<Long> memberIds, Long excludeTeamId) {
        for (TournamentTeamMember bind : teamMemberRepository
                .findByTournamentIdOrderByTeamIdAscPositionAsc(tournamentId)) {
            if (excludeTeamId != null && bind.getTeamId().equals(excludeTeamId)) {
                continue;
            }
            if (memberIds.contains(bind.getMemberId())) {
                Member occupied = memberRepository.findById(bind.getMemberId()).orElse(null);
                String memberName = occupied == null ? "" : occupied.getName();
                TournamentTeam occupiedTeam = teamRepository.findById(bind.getTeamId()).orElse(null);
                String teamName = occupiedTeam == null ? "" : occupiedTeam.getTeamName();
                throw new BizException("会员「" + memberName + "」已在「" + teamName
                        + "」，同一会员不能同时出现在两支队伍");
            }
        }
    }

    private Member[] resolveMembers(List<Long> memberIds) {
        List<Member> members = new ArrayList<>();
        for (Long memberId : memberIds) {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new BizException("会员不存在：id=" + memberId));
            members.add(member);
        }
        return members.toArray(new Member[0]);
    }

    private void bindMembers(TournamentTeam team, List<Long> memberIds, Member[] members) {
        for (int position = 0; position < memberIds.size(); position += 1) {
            TournamentTeamMember bind = new TournamentTeamMember();
            bind.setTeamId(team.getId());
            bind.setTournamentId(team.getTournamentId());
            bind.setMemberId(memberIds.get(position));
            bind.setPosition(position);
            teamMemberRepository.save(bind);
        }
    }

    private String memberNames(Member[] members) {
        return java.util.Arrays.stream(members).map(Member::getName)
                .collect(Collectors.joining("、"));
    }

    private String normalizeRing(String ring) {
        String normalized = safe(ring).toUpperCase();
        if (!RangeDict.isValidRing(normalized)) {
            throw new BizException("环数只能是 X / 10 / 9 / 8 / 7 / 6 / 5 / 4 / 3 / 2 / 1 / M");
        }
        return normalized;
    }

    private int[] sumArrows(List<MatchArrow> arrows, Long homeTeamId, Long awayTeamId) {
        int home = 0;
        int away = 0;
        for (MatchArrow arrow : arrows) {
            if (arrow.getTeamId().equals(homeTeamId)) {
                home += arrow.getRingValue();
            } else if (arrow.getTeamId().equals(awayTeamId)) {
                away += arrow.getRingValue();
            }
        }
        return new int[] {home, away};
    }

    private int[] sumShootoffArrows(List<ShootoffArrow> arrows, Long homeTeamId, Long awayTeamId) {
        int home = 0;
        int away = 0;
        for (ShootoffArrow arrow : arrows) {
            if (arrow.getTeamId().equals(homeTeamId)) {
                home += arrow.getRingValue();
            } else if (arrow.getTeamId().equals(awayTeamId)) {
                away += arrow.getRingValue();
            }
        }
        return new int[] {home, away};
    }

    private int countX(List<MatchArrow> arrows, Long teamId) {
        if (teamId == null) {
            return 0;
        }
        return (int) arrows.stream()
                .filter(arrow -> teamId.equals(arrow.getTeamId()) && "X".equals(arrow.getRing()))
                .count();
    }

    private int countShootoffX(List<ShootoffArrow> arrows, Long teamId) {
        if (teamId == null) {
            return 0;
        }
        return (int) arrows.stream()
                .filter(arrow -> teamId.equals(arrow.getTeamId()) && "X".equals(arrow.getRing()))
                .count();
    }

    private Tournament require(Long id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new BizException("赛事不存在：id=" + id));
    }

    private Tournament lockTournament(Long id) {
        return tournamentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("赛事不存在：id=" + id));
    }

    private TournamentMatch lockMatch(Long id) {
        return matchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("场次不存在：id=" + id));
    }

    private void log(Long tournamentId, Long matchId, String action, String operator, String role,
            String detail) {
        TournamentLog entry = new TournamentLog();
        entry.setTournamentId(tournamentId);
        entry.setMatchId(matchId);
        entry.setAction(action);
        entry.setOperator(operator);
        entry.setRole(role);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String nameOf(Long memberId) {
        return memberRepository.findById(memberId).map(Member::getName).orElse("");
    }

    private List<TournamentTeamMember> membersOf(Long teamId) {
        return teamId == null ? List.of() : teamMemberRepository.findByTeamIdOrderByPositionAsc(teamId);
    }

    // ---------------- 视图组装 ----------------

    private TournamentDtos.TournamentSummary toSummary(Tournament tournament) {
        int teamCount = teamRepository.findByTournamentIdOrderByIdAsc(tournament.getId()).size();
        String championName = null;
        if (tournament.getChampionTeamId() != null) {
            championName = teamRepository.findById(tournament.getChampionTeamId())
                    .map(TournamentTeam::getTeamName).orElse(null);
        }
        String currentRoundName = tournament.getCurrentRound() == null ? null
                : RangeDict.roundName(tournament.getTeamSize(), tournament.getCurrentRound());
        return new TournamentDtos.TournamentSummary(
                tournament.getId(),
                tournament.getName(),
                tournament.getTeamSize(),
                tournament.getEndsPerMatch(),
                tournament.getArrowsPerEnd(),
                tournament.getStatus(),
                RangeDict.tournamentStatusName(tournament.getStatus()),
                tournament.getCurrentRound(),
                currentRoundName,
                teamCount,
                tournament.getChampionTeamId(),
                championName,
                tournament.getCreatedBy(),
                tournament.getCreatedAt(),
                tournament.getStartedAt(),
                tournament.getFinishedAt());
    }

    private TournamentDtos.TeamView toTeamView(TournamentTeam team, List<TournamentTeamMember> binds) {
        Map<Long, Member> memberMap = memberRepository
                .findAllById(binds.stream().map(TournamentTeamMember::getMemberId).toList())
                .stream().collect(Collectors.toMap(Member::getId, Function.identity()));
        List<TournamentDtos.TeamMemberView> members = binds.stream()
                .sorted(Comparator.comparingInt(TournamentTeamMember::getPosition))
                .map(bind -> {
                    Member member = memberMap.get(bind.getMemberId());
                    return new TournamentDtos.TeamMemberView(
                            bind.getMemberId(),
                            member == null ? "" : member.getName(),
                            member == null ? "" : member.getCardNo(),
                            bind.getPosition());
                }).toList();
        Integer seed = team.getSeedNo();
        return new TournamentDtos.TeamView(
                team.getId(),
                team.getTeamName(),
                seed,
                seed == null ? "未排位" : seed + " 号种子",
                team.getFinalRank(),
                members);
    }

    private TournamentDtos.MatchView toMatchView(TournamentMatch match,
            Map<Long, TournamentTeam> teamMap, Map<Long, Integer> matchNoById) {
        Tournament tournament = require(match.getTournamentId());
        TournamentTeam home = match.getHomeTeamId() == null ? null : teamMap.get(match.getHomeTeamId());
        TournamentTeam away = match.getAwayTeamId() == null ? null : teamMap.get(match.getAwayTeamId());

        String homeSource = sourceLabel(match.getHomeTeamId(), match.getHomeFromMatchId(), teamMap, matchNoById);
        String awaySource = sourceLabel(match.getAwayTeamId(), match.getAwayFromMatchId(), teamMap, matchNoById);

        boolean ready = match.getHomeTeamId() != null && match.getAwayTeamId() != null
                && !"CONFIRMED".equals(match.getStatus());
        boolean current = "ONGOING".equals(match.getStatus()) || "AWAIT_CONFIRM".equals(match.getStatus());
        long confirmedEnds = endRepository.countByMatchIdAndStatus(match.getId(), "CONFIRMED");

        return new TournamentDtos.MatchView(
                match.getId(),
                match.getRoundNo(),
                RangeDict.roundName(tournament.getTeamSize(), match.getRoundNo()),
                match.getSlot(),
                match.getMatchNo(),
                match.getHomeTeamId(),
                home == null ? null : home.getTeamName(),
                home == null || home.getSeedNo() == null ? null : home.getSeedNo() + " 号种子",
                match.getAwayTeamId(),
                away == null ? null : away.getTeamName(),
                away == null || away.getSeedNo() == null ? null : away.getSeedNo() + " 号种子",
                homeSource,
                awaySource,
                match.getWinnerTeamId(),
                match.getStatus(),
                RangeDict.matchStatusName(match.getStatus()),
                match.getStage(),
                RangeDict.matchStageName(match.getStage()),
                match.getHomeScore(),
                match.getAwayScore(),
                match.getHomeXCount(),
                match.getAwayXCount(),
                (int) confirmedEnds,
                tournament.getEndsPerMatch(),
                match.getShootoffRoundCount(),
                match.getConfirmedBy(),
                match.getConfirmedAt(),
                ready,
                current);
    }

    /** 槽位晋级来源说明：首轮显示种子位，后续轮显示「第 N 场胜者」 */
    private String sourceLabel(Long teamId, Long fromMatchId,
            Map<Long, TournamentTeam> teamMap, Map<Long, Integer> matchNoById) {
        if (teamId != null) {
            TournamentTeam team = teamMap.get(teamId);
            if (team != null && team.getSeedNo() != null) {
                return team.getSeedNo() + " 号种子";
            }
            return team == null ? "" : team.getTeamName();
        }
        if (fromMatchId != null) {
            Integer no = matchNoById.get(fromMatchId);
            return "第 " + (no == null ? "?" : no) + " 场胜者";
        }
        return "";
    }

    private TournamentDtos.EndView toEndView(MatchEnd end, List<MatchArrow> arrows) {
        return new TournamentDtos.EndView(
                end.getId(),
                end.getEndNo(),
                end.getHomeScore(),
                end.getAwayScore(),
                end.getHomeXCount(),
                end.getAwayXCount(),
                end.getConfirmedBy(),
                end.getConfirmedAt(),
                arrows.stream().map(arrow -> toArrowView(arrow, nameOf(arrow.getMemberId()))).toList());
    }

    private TournamentDtos.ArrowView toArrowView(MatchArrow arrow, String memberName) {
        return new TournamentDtos.ArrowView(
                arrow.getMemberId(), memberName, arrow.getShotIndex(), arrow.getRing(), arrow.getRingValue());
    }

    private TournamentDtos.LogView toLogView(TournamentLog log) {
        return new TournamentDtos.LogView(
                log.getId(),
                log.getMatchId(),
                log.getAction(),
                RangeDict.tournamentActionName(log.getAction()),
                log.getOperator(),
                log.getRole(),
                RangeDict.tournamentRoleName(log.getRole()),
                log.getDetail(),
                log.getCreatedAt());
    }
}
