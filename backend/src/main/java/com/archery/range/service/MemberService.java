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
        return memberRepository.findAllByOrderByIdAsc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<MemberDtos.MemberView> listByLevel(String level) {
        return memberRepository.findByLevelOrderByIdAsc(level).stream().map(this::toView).toList();
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
        member.setBalance(member.getBalance().add(amount).setScale(2, RoundingMode.HALF_UP));
        return toView(memberRepository.save(member));
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
                validCertCount);
    }
}
