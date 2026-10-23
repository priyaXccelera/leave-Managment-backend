package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.leaves.AbstractIntegrationTest;
import com.company.leave.leaves.domain.BalanceAdjustment;
import com.company.leave.leaves.domain.LeaveBalance;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.MasterDtos.AdjustRequest;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.repo.BalanceAdjustmentRepository;
import com.company.leave.leaves.repo.LeaveBalanceRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceServiceTest extends AbstractIntegrationTest {

    private static final BigDecimal ONE = BigDecimal.ONE;

    @Autowired
    private BalanceService balances;
    @Autowired
    private YearEndJob yearEndJob;
    @Autowired
    private LeaveTypeRepository types;
    @Autowired
    private LeaveBalanceRepository balanceRepo;
    @Autowired
    private BalanceAdjustmentRepository adjustmentRepo;

    private final int year = BalanceService.currentYear();

    private LeaveType type(String code) {
        return types.findByCodeIgnoreCase(code).orElseThrow();
    }

    private long employeeWithFullQuota() {
        long id = newEmployeeId();
        balances.initForEmployee(id, LocalDate.of(year - 2, 1, 2));
        return id;
    }

    private LeaveBalance row(long employeeId, String code, int y) {
        return balanceRepo.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, type(code).getId(), y).orElseThrow();
    }

    @Test
    void seededLeaveTypesExist() {
        assertThat(types.findAllByOrderByIdAsc()).extracting(LeaveType::getCode)
                .contains("CL", "SL", "EL", "ML", "PL", "CO", "LOP");
    }

    @Test
    void reserveIncreasesPending() {
        long emp = employeeWithFullQuota();
        balances.reserve(emp, type("CL").getId(), year, new BigDecimal("3"));

        LeaveBalance b = row(emp, "CL", year);
        assertThat(b.getPending()).isEqualByComparingTo("3");
        assertThat(b.getTotal()).isEqualByComparingTo("12");
        assertThat(b.available()).isEqualByComparingTo("9");
        assertThat(balances.available(emp, type("CL").getId(), year)).isEqualByComparingTo("9");
    }

    @Test
    void reserveBeyondAvailableFailsWith422() {
        long emp = employeeWithFullQuota();
        Long cl = type("CL").getId();

        assertThatThrownBy(() -> balances.reserve(emp, cl, year, new BigDecimal("12.5")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
                });
        assertThat(row(emp, "CL", year).getPending()).isEqualByComparingTo("0");

        balances.reserve(emp, cl, year, new BigDecimal("12"));
        assertThat(row(emp, "CL", year).available()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> balances.reserve(emp, cl, year, new BigDecimal("0.5")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void releaseGivesPendingBack() {
        long emp = employeeWithFullQuota();
        Long cl = type("CL").getId();
        balances.reserve(emp, cl, year, new BigDecimal("4"));
        balances.release(emp, cl, year, new BigDecimal("4"));

        LeaveBalance b = row(emp, "CL", year);
        assertThat(b.getPending()).isEqualByComparingTo("0");
        assertThat(b.available()).isEqualByComparingTo("12");
    }

    @Test
    void deductMovesPendingToUsed() {
        long emp = employeeWithFullQuota();
        Long cl = type("CL").getId();
        balances.reserve(emp, cl, year, new BigDecimal("2.5"));
        balances.deduct(emp, cl, year, new BigDecimal("2.5"));

        LeaveBalance b = row(emp, "CL", year);
        assertThat(b.getPending()).isEqualByComparingTo("0");
        assertThat(b.getUsed()).isEqualByComparingTo("2.5");
        assertThat(b.available()).isEqualByComparingTo("9.5");
    }

    @Test
    void restoreReturnsUsedDays() {
        long emp = employeeWithFullQuota();
        Long cl = type("CL").getId();
        balances.reserve(emp, cl, year, new BigDecimal("2"));
        balances.deduct(emp, cl, year, new BigDecimal("2"));
        balances.restore(emp, cl, year, new BigDecimal("2"));

        LeaveBalance b = row(emp, "CL", year);
        assertThat(b.getUsed()).isEqualByComparingTo("0");
        assertThat(b.available()).isEqualByComparingTo("12");
    }

    @Test
    void unlimitedUnpaidTypeIsNotBoundByBalance() {
        long emp = employeeWithFullQuota();
        Long lop = type("LOP").getId();

        balances.reserve(emp, lop, year, new BigDecimal("40"));

        assertThat(row(emp, "LOP", year).getPending()).isEqualByComparingTo("40");
    }

    @Test
    void balanceIsCreatedLazilyForTypeWithoutRow() {
        long emp = newEmployeeId(); // never initialised
        balances.reserve(emp, type("PL").getId(), year, new BigDecimal("5"));

        LeaveBalance b = row(emp, "PL", year);
        assertThat(b.getTotal()).isEqualByComparingTo("10");
        assertThat(b.getPending()).isEqualByComparingTo("5");
    }

    @Test
    void adjustChangesTotalAndLogsReason() {
        long emp = employeeWithFullQuota();
        Long co = type("CO").getId();
        long hr = newEmployeeId();

        BalanceDto dto = balances.adjust(emp, new AdjustRequest(co, null, new BigDecimal("2"), "Worked on weekend"), hr);

        assertThat(dto.total()).isEqualByComparingTo("2");
        assertThat(dto.available()).isEqualByComparingTo("2");
        assertThat(dto.code()).isEqualTo("CO");
        List<BalanceAdjustment> log = adjustmentRepo.findByEmployeeIdAndYearOrderByCreatedAtAsc(emp, year);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getReason()).isEqualTo("Worked on weekend");
        assertThat(log.get(0).getAdjustedBy()).isEqualTo(hr);
        assertThat(log.get(0).getDays()).isEqualByComparingTo("2");
    }

    @Test
    void adjustRequiresReasonAndCannotGoBelowUsedAndPending() {
        long emp = employeeWithFullQuota();
        Long cl = type("CL").getId();
        balances.reserve(emp, cl, year, new BigDecimal("5"));

        assertThatThrownBy(() -> balances.adjust(emp, new AdjustRequest(cl, year, ONE, "  "), 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> balances.adjust(emp, new AdjustRequest(cl, year, new BigDecimal("-8"), "cut"), 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(row(emp, "CL", year).getTotal()).isEqualByComparingTo("12");
        BalanceDto ok = balances.adjust(emp, new AdjustRequest(cl, year, new BigDecimal("-7"), "cut"), 1L);
        assertThat(ok.total()).isEqualByComparingTo("5");
        assertThat(adjustmentRepo.findByEmployeeIdAndYearOrderByCreatedAtAsc(emp, year)).hasSize(1);
    }

    @Test
    void midYearJoinerGetsProratedQuota() {
        long emp = newEmployeeId();
        // joining 1 July => 6 remaining months (Jul..Dec)
        List<BalanceDto> result = balances.initForEmployee(emp, LocalDate.of(year, 7, 1));

        assertThat(result).hasSize(7);
        assertThat(total(result, "CL")).isEqualByComparingTo("6");
        assertThat(total(result, "SL")).isEqualByComparingTo("5");
        assertThat(total(result, "EL")).isEqualByComparingTo("7.5");
        assertThat(total(result, "ML")).isEqualByComparingTo("91");
        assertThat(total(result, "CO")).isEqualByComparingTo("0");
        assertThat(total(result, "LOP")).isEqualByComparingTo("0");
    }

    @Test
    void proratingRoundsToHalfDay() {
        // joining 1 Oct => 3 months: CL 12*3/12 = 3, SL 10*3/12 = 2.5, EL 15*3/12 = 3.75 -> 4.0
        List<BalanceDto> result = balances.initForEmployee(newEmployeeId(), LocalDate.of(year, 10, 15));
        assertThat(total(result, "CL")).isEqualByComparingTo("3");
        assertThat(total(result, "SL")).isEqualByComparingTo("2.5");
        assertThat(total(result, "EL")).isEqualByComparingTo("4");
    }

    @Test
    void priorYearJoinerGetsFullQuota() {
        List<BalanceDto> result = balances.initForEmployee(newEmployeeId(), LocalDate.of(year - 1, 11, 20));
        assertThat(total(result, "CL")).isEqualByComparingTo("12");
        assertThat(total(result, "EL")).isEqualByComparingTo("15");
    }

    @Test
    void initIsIdempotentAndKeepsExistingFigures() {
        long emp = newEmployeeId();
        balances.initForEmployee(emp, LocalDate.of(year - 1, 1, 2));
        balances.reserve(emp, type("CL").getId(), year, new BigDecimal("2"));

        List<BalanceDto> again = balances.initForEmployee(emp, LocalDate.of(year - 1, 1, 2));

        assertThat(again).hasSize(7);
        assertThat(balanceRepo.findByEmployeeIdAndYearOrderByLeaveTypeIdAsc(emp, year)).hasSize(7);
        assertThat(again.stream().filter(b -> b.code().equals("CL")).findFirst().orElseThrow().pending())
                .isEqualByComparingTo("2");
    }

    @Test
    void yearEndCarriesForwardCappedAndIsIdempotent() {
        int closing = 2031;
        long rich = newEmployeeId();
        long modest = newEmployeeId();
        for (long emp : new long[]{rich, modest}) {
            for (String code : List.of("CL", "EL", "LOP")) {
                LeaveType t = type(code);
                balances.ensureBalance(emp, t, closing, t.isUnlimited() ? BigDecimal.ZERO : t.getYearlyQuota(),
                        BigDecimal.ZERO);
            }
        }
        Long el = type("EL").getId();
        Long cl = type("CL").getId();
        // rich: EL available 15 + 50 = 65 -> carry capped at 30
        balances.adjust(rich, new AdjustRequest(el, closing, new BigDecimal("50"), "bonus"), 1L);
        // modest: EL used 10 -> available 5 -> carry 5; CL has leftover but is not carry-forward
        balances.reserve(modest, el, closing, new BigDecimal("10"));
        balances.deduct(modest, el, closing, new BigDecimal("10"));

        int created = yearEndJob.runFor(closing);

        assertThat(created).isGreaterThanOrEqualTo(6);
        LeaveBalance richEl = row(rich, "EL", closing + 1);
        assertThat(richEl.getCarriedForward()).isEqualByComparingTo("30");
        assertThat(richEl.getTotal()).isEqualByComparingTo("15");
        assertThat(richEl.available()).isEqualByComparingTo("45");
        assertThat(row(modest, "EL", closing + 1).getCarriedForward()).isEqualByComparingTo("5");
        LeaveBalance nextCl = row(rich, "CL", closing + 1);
        assertThat(nextCl.getCarriedForward()).isEqualByComparingTo("0");
        assertThat(nextCl.getTotal()).isEqualByComparingTo("12");
        LeaveBalance nextLop = row(rich, "LOP", closing + 1);
        assertThat(nextLop.getTotal()).isEqualByComparingTo("0");
        assertThat(balanceRepo.findByEmployeeIdAndYearOrderByLeaveTypeIdAsc(rich, closing + 1)).hasSize(3);
        assertThat(cl).isNotNull();

        // second run: nothing created, nothing changed (even if the old year changes meanwhile)
        balances.adjust(rich, new AdjustRequest(el, closing, new BigDecimal("-20"), "late correction"), 1L);
        assertThat(yearEndJob.runFor(closing)).isZero();
        assertThat(row(rich, "EL", closing + 1).getCarriedForward()).isEqualByComparingTo("30");
        assertThat(balanceRepo.findByEmployeeIdAndYearOrderByLeaveTypeIdAsc(rich, closing + 1)).hasSize(3);
    }

    private static BigDecimal total(List<BalanceDto> list, String code) {
        return list.stream().filter(b -> b.code().equals(code)).findFirst().orElseThrow().total();
    }
}
