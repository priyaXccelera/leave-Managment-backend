package com.company.leave.leaves.service;

import com.company.leave.leaves.config.LeaveProperties;
import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Holiday;
import com.company.leave.leaves.repo.HolidayRepository;
import com.company.leave.leaves.service.WorkingDayCalculator.Result;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingDayCalculatorTest {

    private static final Set<DayOfWeek> WEEKEND = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final Set<LocalDate> NO_HOLIDAYS = Set.of();

    // 2025-06-02 is a Monday
    private static final LocalDate MON = LocalDate.of(2025, 6, 2);

    private static Result calc(LocalDate from, LocalDate to, boolean half, Set<LocalDate> holidays) {
        return WorkingDayCalculator.calculate(from, to, half, WEEKEND, holidays);
    }

    @Test
    void mondayToFridayIsFiveDays() {
        Result r = calc(MON, MON.plusDays(4), false, NO_HOLIDAYS);
        assertThat(r.workingDays()).isEqualByComparingTo("5");
        assertThat(r.excludedDates()).isEmpty();
    }

    @Test
    void fridayToMondayIsTwoDaysAndExcludesWeekend() {
        Result r = calc(MON.plusDays(4), MON.plusDays(7), false, NO_HOLIDAYS);
        assertThat(r.workingDays()).isEqualByComparingTo("2");
        assertThat(r.excludedDates()).containsExactly(MON.plusDays(5), MON.plusDays(6));
    }

    @Test
    void publicHolidayIsExcluded() {
        LocalDate wednesday = MON.plusDays(2);
        Result r = calc(MON, MON.plusDays(4), false, Set.of(wednesday));
        assertThat(r.workingDays()).isEqualByComparingTo("4");
        assertThat(r.excludedDates()).containsExactly(wednesday);
    }

    @Test
    void optionalHolidayIsCountedBecauseOnlyPublicOnesAreQueried() {
        HolidayRepository repo = Mockito.mock(HolidayRepository.class);
        Holiday optional = new Holiday();
        optional.setDate(MON.plusDays(2));
        optional.setType(HolidayType.OPTIONAL);
        // the calculator asks only for PUBLIC holidays, so the optional one never reaches it
        Mockito.when(repo.findByDateBetweenAndType(MON, MON.plusDays(4), HolidayType.PUBLIC)).thenReturn(List.of());
        WorkingDayCalculator calculator = new WorkingDayCalculator(repo, new LeaveProperties(WEEKEND, 7, 3,
                "uploads", 5, "http://localhost:1", "key", "secret"));

        Result r = calculator.calculate(MON, MON.plusDays(4), false);

        assertThat(r.workingDays()).isEqualByComparingTo("5");
        Mockito.verify(repo).findByDateBetweenAndType(MON, MON.plusDays(4), HolidayType.PUBLIC);
    }

    @Test
    void instanceCalculationUsesRepositoryPublicHolidays() {
        HolidayRepository repo = Mockito.mock(HolidayRepository.class);
        Holiday publicHoliday = new Holiday();
        publicHoliday.setDate(MON);
        publicHoliday.setType(HolidayType.PUBLIC);
        Mockito.when(repo.findByDateBetweenAndType(MON, MON.plusDays(4), HolidayType.PUBLIC))
                .thenReturn(List.of(publicHoliday));
        WorkingDayCalculator calculator = new WorkingDayCalculator(repo, new LeaveProperties(WEEKEND, 7, 3,
                "uploads", 5, "http://localhost:1", "key", "secret"));

        Result r = calculator.calculate(MON, MON.plusDays(4), false);

        assertThat(r.workingDays()).isEqualByComparingTo("4");
        assertThat(r.excludedDates()).containsExactly(MON);
    }

    @Test
    void halfDayOnWorkingDayIsHalf() {
        assertThat(calc(MON, MON, true, NO_HOLIDAYS).workingDays()).isEqualByComparingTo("0.5");
    }

    @Test
    void halfDayOnWeekendOrHolidayIsZero() {
        LocalDate saturday = MON.plusDays(5);
        assertThat(calc(saturday, saturday, true, NO_HOLIDAYS).workingDays()).isEqualByComparingTo("0");
        assertThat(calc(MON, MON, true, Set.of(MON)).workingDays()).isEqualByComparingTo("0");
    }

    @Test
    void weekendOnlyRangeIsZero() {
        Result r = calc(MON.plusDays(5), MON.plusDays(6), false, NO_HOLIDAYS);
        assertThat(r.workingDays()).isEqualByComparingTo("0");
        assertThat(r.excludedDates()).hasSize(2);
    }

    @Test
    void rangeAcrossMonthBoundary() {
        // Thu 2025-05-29 .. Tue 2025-06-03: Thu, Fri, Mon, Tue
        Result r = calc(LocalDate.of(2025, 5, 29), LocalDate.of(2025, 6, 3), false, NO_HOLIDAYS);
        assertThat(r.workingDays()).isEqualByComparingTo("4");
        assertThat(r.excludedDates()).containsExactly(LocalDate.of(2025, 5, 31), LocalDate.of(2025, 6, 1));
    }

    @Test
    void customWeekendIsHonoured() {
        // Friday/Saturday weekend
        Result r = WorkingDayCalculator.calculate(MON, MON.plusDays(6), false,
                Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), NO_HOLIDAYS);
        assertThat(r.workingDays()).isEqualByComparingTo("5");
    }
}
