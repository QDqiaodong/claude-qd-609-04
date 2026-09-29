package com.archery.range.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.archery.range.common.BizException;
import com.archery.range.domain.Equipment;
import com.archery.range.domain.Member;
import com.archery.range.domain.RangeDict;
import com.archery.range.dto.EquipmentDtos;
import com.archery.range.repository.EquipmentRepository;

@Service
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;
    private final MemberService memberService;
    private final CertService certService;

    public EquipmentService(EquipmentRepository equipmentRepository, MemberService memberService,
            CertService certService) {
        this.equipmentRepository = equipmentRepository;
        this.memberService = memberService;
        this.certService = certService;
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.EquipmentView> list() {
        return equipmentRepository.findAllByOrderByEquipCodeAsc().stream().map(EquipmentService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.EquipmentView> listByType(String type) {
        return equipmentRepository.findByTypeOrderByEquipCodeAsc(type).stream().map(EquipmentService::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.EquipmentView> listByStatus(String status) {
        return equipmentRepository.findByStatusOrderByEquipCodeAsc(status).stream().map(EquipmentService::toView).toList();
    }

    @Transactional
    public EquipmentDtos.EquipmentView create(EquipmentDtos.EquipmentSaveReq req) {
        if (equipmentRepository.existsByEquipCode(req.equipCode())) {
            throw new BizException("器材编号已存在：" + req.equipCode());
        }
        if (!RangeDict.isValidEquipType(req.type())) {
            throw new BizException("器材类型只能是 反曲弓 / 复合弓 / 传统弓 / 护具 / 箭支");
        }
        Equipment equipment = new Equipment();
        equipment.setEquipCode(req.equipCode());
        equipment.setType(req.type());
        equipment.setBrand(req.brand());
        equipment.setRentPrice(req.rentPrice().setScale(2, RoundingMode.HALF_UP));
        equipment.setStatus("INSTOCK");
        return toView(equipmentRepository.save(equipment));
    }

    /** 租借：只在库器材可租，按 1 小时租金从会员余额扣。安全锁定中禁止租借。 */
    @Transactional
    public EquipmentDtos.EquipmentView rent(Long id, EquipmentDtos.RentReq req) {
        Equipment equipment = requireForUpdate(id);
        rejectIfLocked(equipment, "租借");
        if (!"INSTOCK".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 当前为「"
                    + RangeDict.equipStatusName(equipment.getStatus()) + "」，不能租借");
        }
        Member member = memberService.require(req.memberId());
        // 租借弓类器材必须持有该弓种的当前有效认证（护具 / 箭支不限制）
        certService.requireBowCert(member, equipment.getType());
        memberService.charge(member, equipment.getRentPrice(), "租借 " + equipment.getEquipCode());

        equipment.setStatus("RENTED");
        equipment.setRenterId(member.getId());
        equipment.setRenterName(member.getName());
        equipment.setRentedAt(LocalDateTime.now());
        return toView(equipmentRepository.save(equipment));
    }

    /** 归还：只有租出的器材能还。安全锁定中禁止归还。 */
    @Transactional
    public EquipmentDtos.EquipmentView giveBack(Long id) {
        Equipment equipment = requireForUpdate(id);
        rejectIfLocked(equipment, "归还");
        if (!"RENTED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 未处于租出状态，无需归还");
        }
        equipment.setStatus("INSTOCK");
        equipment.setRenterId(null);
        equipment.setRenterName(null);
        equipment.setRentedAt(null);
        return toView(equipmentRepository.save(equipment));
    }

    /** 报修 / 修好：在库 → 维修，维修 → 在库。安全锁定中禁止切换维修状态。 */
    @Transactional
    public EquipmentDtos.EquipmentView toggleRepair(Long id) {
        Equipment equipment = requireForUpdate(id);
        rejectIfLocked(equipment, "切换维修状态");
        if ("RENTED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 租出中，请先归还再报修");
        }
        if ("REPAIR".equals(equipment.getStatus())) {
            equipment.setStatus("INSTOCK");
        } else {
            equipment.setStatus("REPAIR");
            equipment.setRenterId(null);
            equipment.setRenterName(null);
            equipment.setRentedAt(null);
        }
        return toView(equipmentRepository.save(equipment));
    }

    @Transactional(readOnly = true)
    public Equipment require(Long id) {
        return equipmentRepository.findById(id)
                .orElseThrow(() -> new BizException("器材不存在：id=" + id));
    }

    /** 写操作入口：悲观锁器材行，与安全联锁的锁定 / 恢复互斥 */
    private Equipment requireForUpdate(Long id) {
        return equipmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("器材不存在：id=" + id));
    }

    private static void rejectIfLocked(Equipment equipment, String action) {
        if ("LOCKED".equals(equipment.getStatus())) {
            throw new BizException("器材 [" + equipment.getEquipCode() + "] 处于安全锁定（停射事件未放行），禁止" + action);
        }
    }

    private static EquipmentDtos.EquipmentView toView(Equipment equipment) {
        BigDecimal price = equipment.getRentPrice() == null ? BigDecimal.ZERO : equipment.getRentPrice();
        return new EquipmentDtos.EquipmentView(
                equipment.getId(),
                equipment.getEquipCode(),
                equipment.getType(),
                RangeDict.equipTypeName(equipment.getType()),
                equipment.getBrand(),
                price,
                equipment.getStatus(),
                RangeDict.equipStatusName(equipment.getStatus()),
                equipment.getRenterId(),
                equipment.getRenterName(),
                equipment.getRentedAt());
    }
}
