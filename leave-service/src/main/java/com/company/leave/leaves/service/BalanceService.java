package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.leaves.domain.BalanceAdjustment;
import com.company.leave.leaves.domain.LeaveBalance;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.MasterDtos.AdjustRequest;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.BalanceAdjustmentRepository;
import com.company.leave.leaves.repo.LeaveBalanceRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Leave balance bookkeeping. Every mutating operation row-locks the balance (SELECT ... FOR UPDATE) so
 * concurrent requests of the same employee cannot over-book. Days are BigDecimal with scale 1.
 */
@Service
public class BalanceService {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(1);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final int MONTHS_IN_YEAR = 12;

    private final LeaveBalanceRepository balances;
    private final LeaveTypeRepository leaveTypes;
    private final BalanceAdjustmentRepository adjustments;

    public BalanceService(LeaveBalanceRepository balances, LeaveTypeRepository leaveTypes,
                          BalanceAdjustmentRepository adjustments) {
        this.balances = balances;
        this.leaveTypes = leaveTypes;
        this.adjustments = adjustments;
    }

    /** Current calendar year (UTC). */
    public static int currentYear() {
        return LocalDate.now(ZoneOffset.UTC).getYear();
    }

    /** Existing balances of the employee for the year (default: current), ordered by leave type. */
    @Transactional(readOnly = true)
    public List<BalanceDto> forEmployee(Long employeeId, Integer year) {
        int y = year == null ? currentYear() : year;
        List<LeaveBalance> rows = balances.findByEmployeeIdAndYearOrderByLeaveTypeIdAsc(employeeId, y);
        Map<Long, LeaveType> typesById = leaveTypes.findAllById(
                        rows.stream().map(LeaveBalance::getLeaveTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(LeaveType::getId, t -> t));
        return rows.stream()
                .filter(b -> typesById.containsKey(b.getLeaveTypeId()))
                .map(b -> MasterMapper.toDto(b, typesById.get(b.getLeaveTypeId())))
                .toList();
    }

    /**
     * Days still available for the type/year. A missing balance row counts as the type's default entitlement.
     * For unlimited types the number is informational only.
     */
    @Transactional(readOnly = true)
    public BigDecimal available(Long employeeId, Long leaveTypeId, int year) {
        return balances.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                .map(b -> scale(b.available()))
                .orElseGet(() -> scale(defaultTotal(requireType(leaveTypeId))));
    }

    /**
     * Moves {@code days} into {@code pending}.
     *
     * @throws ApiException 422 INSUFFICIENT_BALANCE unless the type is unlimited or unpaid
     */
    @Transactional
    public void reserve(Long employeeId, Long leaveTypeId, int year, BigDecimal days) {
        LeaveType type = requireType(leaveTypeId);
        LeaveBalance b = lockOrCreate(employeeId, type, year);
        BigDecimal requested = scale(days);
        if (!type.isUnlimited() && type.isPaid() && b.available().compareTo(requested) < 0) {
            throw ApiException.unprocessable("INSUFFICIENT_BALANCE", "Insufficient " + type.getName()
                    + " balance: " + scale(b.available()) + " day(s) available, " + requested + " requested");
        }
        b.setPending(b.getPending().add(requested));
        balances.save(b);
    }

    /** Returns reserved days (pending) to the available pool. */
    @Transactional
    public void release(Long employeeId, Long leaveTypeId, int year, BigDecimal days) {
        LeaveBalance b = lockOrCreate(employeeId, requireType(leaveTypeId), year);
        b.setPending(floorZero(b.getPending().subtract(scale(days))));
        balances.save(b);
    }

    /** Final approval: converts reserved days from pending to used. */
    @Transactional
    public void deduct(Long employeeId, Long leaveTypeId, int year, BigDecimal days) {
        LeaveBalance b = lockOrCreate(employeeId, requireType(leaveTypeId), year);
        BigDecimal d = scale(days);
        b.setPending(floorZero(b.getPending().subtract(d)));
        b.setUsed(b.getUsed().add(d));
        balances.save(b);
    }

    /** Cancellation of an approved leave: gives used days back. */
    @Transactional
    public void restore(Long employeeId, Long leaveTypeId, int year, BigDecimal days) {
        LeaveBalance b = lockOrCreate(employeeId, requireType(leaveTypeId), year);
        b.setUsed(floorZero(b.getUsed().subtract(scale(days))));
        balances.save(b);
    }

    /**
     * HR adjustment: changes {@code total} by {@code days} (may be negative) and logs it.
     *
     * @throws ApiException 422 INSUFFICIENT_BALANCE when the result would drop below used + pending
     */
    @Transactional
    public BalanceDto adjust(Long employeeId, AdjustRequest request, Long actorId) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw ApiException.validation("Reason is mandatory", List.of(new FieldError("reason", "must not be blank")));
        }
        BigDecimal delta = scale(request.days());
        if (delta.signum() == 0) {
            throw ApiException.validation("Adjustment must not be zero", List.of(new FieldError("days", "must not be zero")));
        }
        int year = request.year() == null ? currentYear() : request.year();
        LeaveType type = requireType(request.leaveTypeId());
        LeaveBalance b = lockOrCreate(employeeId, type, year);
        BigDecimal newTotal = b.getTotal().add(delta);
        if (b.getCarriedForward().add(newTotal).compareTo(b.getUsed().add(b.getPending())) < 0) {
            throw ApiException.unprocessable("INSUFFICIENT_BALANCE",
                    "Adjustment would reduce the balance below the days already used or pending");
        }
        b.setTotal(newTotal);
        balances.save(b);

        BalanceAdjustment log = new BalanceAdjustment();
        log.setEmployeeId(employeeId);
        log.setLeaveTypeId(type.getId());
        log.setYear(year);
        log.setDays(delta);
        log.setReason(request.reason().trim());
        log.setAdjustedBy(actorId);
        adjustments.save(log);
        LOG.info("Balance of employee {} ({} {}) adjusted by {} by user {}", employeeId, type.getCode(), year,
                delta, actorId);
        return MasterMapper.toDto(b, type);
    }

    /**
     * Creates the current-year balances of a new employee for every active leave type; idempotent.
     * Employees joining in the current year get {@code quota * remainingMonths / 12} rounded to 0.5
     * (remaining months include the joining month); unlimited types start at 0.
     */
    @Transactional
    public List<BalanceDto> initForEmployee(Long employeeId, LocalDate joiningDate) {
        int year = currentYear();
        for (LeaveType type : leaveTypes.findByActiveTrueOrderByIdAsc()) {
            ensureBalance(employeeId, type, year, proratedTotal(type, joiningDate, year), ZERO);
        }
        return forEmployee(employeeId, year);
    }

    /** Returns the balance row for employee/type/year, creating it with the given figures if absent. */
    @Transactional
    public LeaveBalance ensureBalance(Long employeeId, LeaveType type, int year, BigDecimal total,
                                      BigDecimal carriedForward) {
        return balances.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, type.getId(), year)
                .orElseGet(() -> create(employeeId, type, year, total, carriedForward));
    }

    private LeaveBalance lockOrCreate(Long employeeId, LeaveType type, int year) {
        return balances.lockByEmployeeIdAndLeaveTypeIdAndYear(employeeId, type.getId(), year)
                .orElseGet(() -> create(employeeId, type, year, defaultTotal(type), ZERO));
    }

    private LeaveBalance create(Long employeeId, LeaveType type, int year, BigDecimal total, BigDecimal carried) {
        LeaveBalance b = new LeaveBalance();
        b.setEmployeeId(employeeId);
        b.setLeaveTypeId(type.getId());
        b.setYear(year);
        b.setTotal(scale(total));
        b.setCarriedForward(scale(carried));
        b.setUsed(ZERO);
        b.setPending(ZERO);
        return balances.saveAndFlush(b);
    }

    /** Full-year entitlement of the type (unlimited types track usage only). */
    static BigDecimal defaultTotal(LeaveType type) {
        return type.isUnlimited() ? ZERO : scale(type.getYearlyQuota());
    }

    static BigDecimal proratedTotal(LeaveType type, LocalDate joiningDate, int year) {
        BigDecimal full = defaultTotal(type);
        if (joiningDate == null || joiningDate.getYear() != year || full.signum() == 0) {
            return full;
        }
        int remainingMonths = MONTHS_IN_YEAR - joiningDate.getMonthValue() + 1;
        BigDecimal raw = full.multiply(BigDecimal.valueOf(remainingMonths))
                .divide(BigDecimal.valueOf(MONTHS_IN_YEAR), 4, RoundingMode.HALF_UP);
        return raw.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 1, RoundingMode.HALF_UP);
    }

    private LeaveType requireType(Long leaveTypeId) {
        return leaveTypes.findById(Objects.requireNonNull(leaveTypeId))
                .orElseThrow(() -> ApiException.notFound("Leave type not found"));
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal floorZero(BigDecimal v) {
        return v.signum() < 0 ? ZERO : v;
    }
}
