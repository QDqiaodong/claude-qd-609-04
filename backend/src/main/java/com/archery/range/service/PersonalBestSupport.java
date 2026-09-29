package com.archery.range.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.archery.range.domain.RangeDict;
import com.archery.range.domain.Round;

/**
 * 个人最好成绩（PB）的统一比较口径。
 *
 * 可比组别至少由「会员 + 弓种 + 射距 + 规定箭数」四要素组成，
 * 6 支与 12 支、不同弓种、不同射距之间绝不混比。
 * 只有已提交（SUBMITTED）且箭数完整的回合才能参与；进行中 / 暂停 / 数据不完整 /
 * 关联对象缺失 / 射距或弓种无法确定的回合一律排除，绝不能被静默归到别的组。
 *
 * 同组排名规则：总分高者胜；总分相同则完成时间更早者胜；再相同按回合 id 兜底，
 * 因此结果完全确定，重复执行不会来回切换。
 */
public final class PersonalBestSupport {

    /** 历史数据中弓种为空（NULL / 空串）的旧回合，按系统原有默认弓种兼容 */
    public static final String DEFAULT_BOW_TYPE = "RECURVE";

    public static final String REASON_NOT_SUBMITTED = "NOT_SUBMITTED";
    public static final String REASON_INCOMPLETE = "INCOMPLETE_ARROWS";
    public static final String REASON_MEMBER_MISSING = "MEMBER_MISSING";
    public static final String REASON_LANE_MISSING = "LANE_MISSING";
    public static final String REASON_DISTANCE_UNKNOWN = "DISTANCE_UNKNOWN";
    public static final String REASON_BOW_UNKNOWN = "BOW_TYPE_UNKNOWN";
    public static final String REASON_ARROW_COUNT_INVALID = "ARROW_COUNT_INVALID";

    /** 可比组别键：会员 + 归一化弓种 + 射距 + 规定箭数 */
    public record GroupKey(Long memberId, String bowType, Integer distance, Integer arrowCount) {
        public String label() {
            return RangeDict.bowTypeName(bowType) + " · " + distance + " 米 · " + arrowCount + " 支";
        }
    }

    /** 参与资格判定结果：可参与时 key 为组别键；不可参与时 reasonCode/reason 说明原因 */
    public record Eligibility(boolean eligible, GroupKey key, String reasonCode, String reason) {
        static Eligibility ok(GroupKey key) {
            return new Eligibility(true, key, null, null);
        }

        static Eligibility reject(String code, String reason) {
            return new Eligibility(false, null, code, reason);
        }
    }

    private PersonalBestSupport() {
    }

    /**
     * 归一化弓种：NULL / 空白按系统默认弓种 RECURVE 兼容（仅用于旧数据）；
     * 无法识别的弓种不归任何组，返回 null 由调用方排除。
     */
    public static String normalizeBowType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BOW_TYPE;
        }
        String bow = raw.trim().toUpperCase();
        return RangeDict.isValidBowType(bow) ? bow : null;
    }

    /** 判定回合能否参与个人最好比较，并给出其可比组别或排除原因（绝不静默归组） */
    public static Eligibility check(Round round) {
        if (round == null) {
            return Eligibility.reject(REASON_LANE_MISSING, "回合记录缺失，无法参与比较");
        }
        if (!"SUBMITTED".equals(round.getStatus())) {
            return Eligibility.reject(REASON_NOT_SUBMITTED,
                    "回合 [" + round.getRoundNo() + "] 状态为「"
                            + RangeDict.roundStatusName(round.getStatus())
                            + "」，只有已完成（已提交）的回合才能参与个人最好比较");
        }
        if (round.getMember() == null || round.getMember().getId() == null) {
            return Eligibility.reject(REASON_MEMBER_MISSING,
                    "回合 [" + round.getRoundNo() + "] 关联会员缺失，无法确定可比组别，保留原始成绩不参与比较");
        }
        if (round.getLane() == null || round.getLane().getId() == null) {
            return Eligibility.reject(REASON_LANE_MISSING,
                    "回合 [" + round.getRoundNo() + "] 关联箭道缺失，无法确定射距，保留原始成绩不参与比较");
        }
        Integer distance = round.getLane().getDistance();
        if (!RangeDict.isValidDistance(distance)) {
            return Eligibility.reject(REASON_DISTANCE_UNKNOWN,
                    "回合 [" + round.getRoundNo() + "] 射距无法确定（" + distance
                            + " 米不是有效射距），保留原始成绩不参与比较");
        }
        String bow = normalizeBowType(round.getBowType());
        if (bow == null) {
            return Eligibility.reject(REASON_BOW_UNKNOWN,
                    "回合 [" + round.getRoundNo() + "] 弓种「" + round.getBowType()
                            + "」无法识别，不能静默归入默认弓种，保留原始成绩不参与比较");
        }
        Integer required = round.getArrowCount();
        if (!RangeDict.isValidGroupSize(required)) {
            return Eligibility.reject(REASON_ARROW_COUNT_INVALID,
                    "回合 [" + round.getRoundNo() + "] 规定箭数为 " + required
                            + "，不是 6 支或 12 支的完整组别，保留原始成绩不参与比较");
        }
        int shotCount = round.getArrows() == null ? 0 : round.getArrows().size();
        if (shotCount < required) {
            return Eligibility.reject(REASON_INCOMPLETE,
                    "回合 [" + round.getRoundNo() + "] 只记录了 " + shotCount + " / " + required
                            + " 支箭，箭数不完整，保留原始成绩不参与比较");
        }
        return Eligibility.ok(new GroupKey(round.getMember().getId(), bow, distance, required));
    }

    /**
     * 同一可比组内确定唯一最好回合：总分最高；同分取完成最早；再同分取 id 最小。
     * 结果只依赖数据本身，天然可重复执行。
     */
    public static Round winnerOf(List<Round> sameGroup) {
        return sameGroup.stream().min(BEST_WINNER).orElse(null);
    }

    /**
     * 把一组回合的 personalBest 标记落到唯一胜者上，其余清零。
     * 入参必须是同一可比组内、已通过资格判定的回合。
     *
     * @return 标记被改动的回合数（0 表示本次执行结果与现状一致）
     */
    public static int applyBestMarks(List<Round> sameGroup) {
        Round winner = winnerOf(sameGroup);
        int changed = 0;
        for (Round round : sameGroup) {
            boolean target = winner != null && Objects.equals(round.getId(), winner.getId());
            if (Boolean.TRUE.equals(round.getPersonalBest()) != target) {
                round.setPersonalBest(target);
                changed += 1;
            }
        }
        return changed;
    }

    /** 完成时间：优先 completed_at；历史缺失时退回开始时间，保证排序确定且不改动原始数据 */
    public static LocalDateTime effectiveCompletedAt(Round round) {
        return round.getCompletedAt() != null ? round.getCompletedAt() : round.getStartTime();
    }

    private static final Comparator<Round> BEST_WINNER =
            Comparator.comparingInt((Round r) -> r.getTotalScore() == null ? 0 : r.getTotalScore()).reversed()
                    .thenComparing(PersonalBestSupport::effectiveCompletedAt)
                    .thenComparing(Round::getId);
}
