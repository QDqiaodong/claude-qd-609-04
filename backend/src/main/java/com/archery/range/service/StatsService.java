package com.archery.range.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.domain.Lane;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.RoundDtos;
import com.archery.range.repository.ArrowScoreRepository;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.CourseRepository;
import com.archery.range.repository.EquipmentRepository;
import com.archery.range.repository.LaneRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;
import com.archery.range.repository.SafetyEventRepository;
import com.archery.range.repository.TournamentRepository;

/**
 * 看板：各模块计数器 + 成绩榜。全部走派生查询，结果在内存里排序取值。
 */
@Service
public class StatsService {

    private final LaneRepository laneRepository;
    private final MemberRepository memberRepository;
    private final RoundRepository roundRepository;
    private final ArrowScoreRepository arrowScoreRepository;
    private final CourseRepository courseRepository;
    private final EquipmentRepository equipmentRepository;
    private final SafetyEventRepository safetyEventRepository;
    private final TournamentRepository tournamentRepository;
    private final CertApplicationRepository certApplicationRepository;
    private final RoundService roundService;

    public StatsService(LaneRepository laneRepository,
            MemberRepository memberRepository,
            RoundRepository roundRepository,
            ArrowScoreRepository arrowScoreRepository,
            CourseRepository courseRepository,
            EquipmentRepository equipmentRepository,
            SafetyEventRepository safetyEventRepository,
            TournamentRepository tournamentRepository,
            CertApplicationRepository certApplicationRepository,
            RoundService roundService) {
        this.laneRepository = laneRepository;
        this.memberRepository = memberRepository;
        this.roundRepository = roundRepository;
        this.arrowScoreRepository = arrowScoreRepository;
        this.courseRepository = courseRepository;
        this.equipmentRepository = equipmentRepository;
        this.safetyEventRepository = safetyEventRepository;
        this.tournamentRepository = tournamentRepository;
        this.certApplicationRepository = certApplicationRepository;
        this.roundService = roundService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> board() {
        List<Lane> lanes = laneRepository.findAllByOrderByLaneNoAsc();
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("laneTotal", lanes.size());
        counters.put("laneOpen", lanes.stream().filter(lane -> "OPEN".equals(lane.getStatus())).count());
        counters.put("laneOccupied", lanes.stream().filter(lane -> "OCCUPIED".equals(lane.getStatus())).count());
        counters.put("laneMaintenance", lanes.stream().filter(lane -> "MAINTENANCE".equals(lane.getStatus())).count());
        counters.put("memberTotal", memberRepository.count());
        counters.put("roundTotal", roundRepository.count());
        counters.put("roundOngoing", roundRepository.countByStatus("ONGOING"));
        counters.put("arrowTotal", arrowScoreRepository.count());
        counters.put("xCount", arrowScoreRepository.countByRing("X"));
        counters.put("courseTotal", courseRepository.count());
        counters.put("equipTotal", equipmentRepository.count());
        counters.put("equipRented", equipmentRepository.findByStatusOrderByEquipCodeAsc("RENTED").size());
        counters.put("safetyActive", safetyEventRepository.countByStatusNot("RELEASED"));
        counters.put("certPending", certApplicationRepository.countByStatus("PENDING"));
        counters.put("tournamentTotal", tournamentRepository.count());
        counters.put("tournamentOngoing", tournamentRepository.countByStatus("ONGOING"));

        List<RoundDtos.RoundView> top = roundRepository.findByStatusOrderByStartTimeDesc("SUBMITTED").stream()
                .sorted(Comparator.comparingInt((com.archery.range.domain.Round r) ->
                        r.getTotalScore() == null ? 0 : r.getTotalScore()).reversed())
                .limit(5)
                .map(roundService::toView)
                .toList();

        Map<String, Object> board = new LinkedHashMap<>();
        board.put("counters", counters);
        board.put("topRounds", top);
        board.put("distances", RangeDict.DISTANCES);
        board.put("keypadRings", RangeDict.KEYPAD_RINGS);
        board.put("groupSizes", RangeDict.GROUP_SIZES);
        return board;
    }
}
