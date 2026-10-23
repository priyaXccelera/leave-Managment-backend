package com.company.leave.leaves.service;

import com.company.leave.leaves.domain.LeaveBalance;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.repo.LeaveBalanceRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Rolls balances over to the new year: for every closing-year balance a next-year balance is created with the
 * type's yearly quota plus the carried-forward days (carry-forward types only, capped at the type's maximum).
 * Idempotent - existing next-year balances are left untouched.
 */
@Component
public class YearEndJob {

    private static final Logger LOG = LoggerFactory.getLogger(YearEndJob.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(1);

    private final LeaveBalanceRepository balances;
    private final LeaveTypeRepository leaveTypes;
    private final BalanceService balanceService;

    public YearEndJob(LeaveBalanceRepository balances, LeaveTypeRepository leaveTypes,
                      BalanceService balanceService) {
        this.balances = balances;
        this.leaveTypes = leaveTypes;
        this.balanceService = balanceService;
    }

    @Scheduled(cron = "${leave.year-end-cron}", zone = "UTC")
    public void run() {
        runFor(BalanceService.currentYear() - 1);
    }

    /** @return number of next-year balances created */
    @Transactional
    public int runFor(int closingYear) {
        int nextYear = closingYear + 1;
        Map<Long, LeaveType> types = new HashMap<>();
        leaveTypes.findAll().forEach(t -> types.put(t.getId(), t));
        int created = 0;
        for (LeaveBalance closing : balances.findByYear(closingYear)) {
            LeaveType type = types.get(closing.getLeaveTypeId());
            if (type == null || !type.isActive()
                    || balances.existsByEmployeeIdAndLeaveTypeIdAndYear(closing.getEmployeeId(), type.getId(),
                    nextYear)) {
                continue;
            }
            BigDecimal quota = type.isUnlimited() ? ZERO : type.getYearlyQuota();
            balanceService.ensureBalance(closing.getEmployeeId(), type, nextYear, quota, carryOver(closing, type));
            created++;
        }
        LOG.info("Year-end rollover {} -> {}: {} balance(s) created", closingYear, nextYear, created);
        return created;
    }

    private static BigDecimal carryOver(LeaveBalance closing, LeaveType type) {
        if (!type.isCarryForwardAllowed() || type.isUnlimited()) {
            return ZERO;
        }
        return closing.available().max(BigDecimal.ZERO).min(type.getMaxCarryForwardDays());
    }
}
