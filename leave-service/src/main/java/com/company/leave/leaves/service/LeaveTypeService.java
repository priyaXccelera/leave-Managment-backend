package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.MasterDtos.LeaveTypeDto;
import com.company.leave.leaves.dto.MasterDtos.LeaveTypeRequest;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LeaveTypeService {

    private final LeaveTypeRepository leaveTypes;

    public LeaveTypeService(LeaveTypeRepository leaveTypes) {
        this.leaveTypes = leaveTypes;
    }

    /** Active types for everyone; HR also sees inactive ones. */
    @Transactional(readOnly = true)
    public List<LeaveTypeDto> list(AuthUser user) {
        List<LeaveType> types = user.isHr() ? leaveTypes.findAllByOrderByIdAsc()
                : leaveTypes.findByActiveTrueOrderByIdAsc();
        return types.stream().map(MasterMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public LeaveTypeDto get(Long id) {
        return MasterMapper.toDto(find(id));
    }

    /** @throws ApiException 409 DUPLICATE_CODE when the code (case-insensitive) is taken */
    @Transactional
    public LeaveTypeDto create(LeaveTypeRequest request) {
        String code = request.code().trim();
        if (leaveTypes.existsByCodeIgnoreCase(code)) {
            throw duplicate(code);
        }
        LeaveType type = new LeaveType();
        apply(type, request, code);
        return MasterMapper.toDto(leaveTypes.save(type));
    }

    /** @throws ApiException 404 when unknown, 409 DUPLICATE_CODE when the code belongs to another type */
    @Transactional
    public LeaveTypeDto update(Long id, LeaveTypeRequest request) {
        LeaveType type = find(id);
        String code = request.code().trim();
        if (leaveTypes.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw duplicate(code);
        }
        apply(type, request, code);
        return MasterMapper.toDto(leaveTypes.save(type));
    }

    private LeaveType find(Long id) {
        return leaveTypes.findById(id).orElseThrow(() -> ApiException.notFound("Leave type not found"));
    }

    private static ApiException duplicate(String code) {
        return ApiException.conflict("DUPLICATE_CODE", "A leave type with code '" + code + "' already exists");
    }

    private static void apply(LeaveType t, LeaveTypeRequest r, String code) {
        t.setName(r.name().trim());
        t.setCode(code);
        t.setYearlyQuota(orZero(r.yearlyQuota()));
        t.setUnlimited(Boolean.TRUE.equals(r.unlimited()));
        t.setPaid(r.paid() == null || r.paid());
        t.setCarryForwardAllowed(Boolean.TRUE.equals(r.carryForwardAllowed()));
        t.setMaxCarryForwardDays(orZero(r.maxCarryForwardDays()));
        t.setHalfDayAllowed(r.halfDayAllowed() == null || r.halfDayAllowed());
        t.setAttachmentRequired(Boolean.TRUE.equals(r.attachmentRequired()));
        t.setAttachmentRequiredAfterDays(r.attachmentRequiredAfterDays());
        t.setMinNoticeDays(r.minNoticeDays() == null ? 0 : r.minNoticeDays());
        t.setActive(r.active() == null || r.active());
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(1) : MasterMapper.days(v);
    }
}
