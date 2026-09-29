package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.MemberDtos;
import com.archery.range.repository.CertApplicationRepository;
import com.archery.range.repository.MemberRepository;
import com.archery.range.repository.RoundRepository;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final RoundRepository roundRepository;
    private final CertApplicationRepository certApplicationRepository;

    public MemberService(MemberRepository memberRepository, RoundRepository roundRepository,
            CertApplicationRepository certApplicationRepository) {
        this.memberRepository = memberRepository;
        this.roundRepository = roundRepository;
        this.certApplicationRepository = certApplicationRepository;
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.MemberView> list() {
        // 会员列表默认只列在用档案；已合并封存的历史档案通过 includeMerged 或专门入口查看
        return list(true, false);
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.MemberView> list(boolean activeOnly, boolean mergedOnly) {
        List<Member> members;
        if (mergedOnly) {
            members = memberRepository.findAllByStatusOrderByIdAsc(Member.STATUS_MERGED);
        } else if (activeOnly) {
            members = memberRepository.findByStatusOrderByIdAsc(Member.STATUS_ACTIVE);
        } else {
            members = memberRepository.findAllByOrderByIdAsc();
        }
        return members.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.MemberView> listByLevel(String level) {
        return memberRepository.findByLevelAndStatusOrderByIdAsc(level, Member.STATUS_ACTIVE).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public MemberDtos.MemberView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public MemberDtos.MemberView create(MemberDtos.MemberSaveReq req) {
        if (memberRepository.existsByCardNo(req.cardNo())) {
            throw new BizException("会员卡号已存在：" + req.cardNo());
        }
        if (memberRepository.existsByPhone(req.phone())) {
            throw new BizException("手机号已被注册：" + req.phone());
        }
        if (!RangeDict.isValidMemberLevel(req.level())) {
            throw new BizException("会员等级只能是 普通 / 银 / 金");
        }
        Member member = new Member();
        member.setCardNo(req.cardNo());
        member.setName(req.name());
        member.setPhone(req.phone());
        member.setLevel(req.level());
        member.setBalance(req.balance().setScale(2, RoundingMode.HALF_UP));
        member.setRegisterDate(LocalDate.now());
        member.setTotalSpend(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return toView(memberRepository.save(member));
    }

    @Transactional
    public MemberDtos.MemberView recharge(Long id, MemberDtos.RechargeReq req) {
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(RangeDict.RECHARGE_MIN) < 0 || amount.compareTo(RangeDict.RECHARGE_MAX) > 0) {
            throw new BizException("单次充值金额需在 100 ~ 10000 元之间");
        }
        Member member = require(id);
        rejectIfMerged(member, "充值");
        member.setBalance(member.getBalance().add(amount).setScale(2, RoundingMode.HALF_UP));
        return toView(memberRepository.save(member));
    }

    /**
     * 已合并封存的来源档案不能再发生任何业务动作。
     * 充值 / 开台 / 报名 / 租借 / 开始新回合等写入口在拿到会员后统一调用本方法拦截。
     */
    public void rejectIfMerged(Member member, String action) {
        if (member != null && Member.STATUS_MERGED.equals(member.getStatus())) {
            String target = member.getMergedIntoId() == null ? ""
                    : "，请使用保留档案 " + mergedTargetCard(member.getMergedIntoId());
            throw new BizException("会员卡 [" + member.getCardNo() + "] 已于 "
                    + (member.getMergedAt() == null ? "" : member.getMergedAt().toLocalDate())
                    + " 合并封存（原因：" + (member.getMergeReason() == null ? "" : member.getMergeReason())
                    + "），不能再" + action + target);
        }
    }

    private String mergedTargetCard(Long targetId) {
        return memberRepository.findById(targetId)
                .map(m -> m.getCardNo() + "（" + m.getName() + "）")
                .orElse("id=" + targetId);
    }

    /**
     * 消费扣款：余额不足直接拦；扣完按累计消费自动升级等级（只升不降）。
     */
    @Transactional
    public void charge(Member member, BigDecimal amount, String bizName) {
        BigDecimal cost = amount.setScale(2, RoundingMode.HALF_UP);
        if (member.getBalance().compareTo(cost) < 0) {
            throw new BizException("会员 [" + member.getName() + "] 余额不足，" + bizName + "需 " + cost + " 元");
        }
        member.setBalance(member.getBalance().subtract(cost).setScale(2, RoundingMode.HALF_UP));
        member.setTotalSpend(member.getTotalSpend().add(cost).setScale(2, RoundingMode.HALF_UP));
        String upgraded = RangeDict.levelByTotalSpend(member.getTotalSpend());
        if (RangeDict.memberLevelRank(upgraded) > RangeDict.memberLevelRank(member.getLevel())) {
            member.setLevel(upgraded);
        }
        memberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public Member require(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：id=" + id));
    }

    private MemberDtos.MemberView toView(Member member) {
        // 个人最好按可比组别（弓种 + 射距 + 箭数）各自独立，这里统计会员当前持有的最好条数；
        // 不再跨组取一个最高总分（12 支组天然压过 6 支组）。
        java.util.Set<com.archery.range.service.PersonalBestSupport.GroupKey> bestGroups = new java.util.HashSet<>();
        for (com.archery.range.domain.Round r :
                roundRepository.findByMemberIdAndStatusOrderByStartTimeDesc(member.getId(), "SUBMITTED")) {
            com.archery.range.service.PersonalBestSupport.Eligibility check =
                    com.archery.range.service.PersonalBestSupport.check(r);
            if (check.eligible() && Boolean.TRUE.equals(r.getPersonalBest())) {
                bestGroups.add(check.key());
            }
        }
        // 当前有效认证：已通过且未过期（撤回 / 被取代 / 过期均不计）
        int validCertCount = certApplicationRepository
                .findByMemberIdAndStatusAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                        member.getId(), "APPROVED", LocalDateTime.now())
                .size();
        String mergedIntoCardNo = null;
        if (member.getMergedIntoId() != null) {
            mergedIntoCardNo = memberRepository.findById(member.getMergedIntoId())
                    .map(Member::getCardNo).orElse(null);
        }
        boolean merged = Member.STATUS_MERGED.equals(member.getStatus());
        return new MemberDtos.MemberView(
                member.getId(),
                member.getCardNo(),
                member.getName(),
                member.getPhone(),
                member.getLevel(),
                RangeDict.memberLevelName(member.getLevel()),
                member.getBalance(),
                member.getRegisterDate(),
                member.getTotalSpend(),
                RangeDict.memberDiscount(member.getLevel()),
                roundRepository.countByMemberId(member.getId()),
                bestGroups.size(),
                validCertCount,
                member.getStatus(),
                merged ? "已合并封存" : "在用",
                member.getMergedIntoId(),
                mergedIntoCardNo,
                member.getMergedAt(),
                member.getMergeReason(),
                member.getMergedBy());
    }
}
