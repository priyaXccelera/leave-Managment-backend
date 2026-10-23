package com.company.leave.leaves.service;

import com.company.leave.leaves.config.LeaveProperties;
import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Holiday;
import com.company.leave.leaves.repo.HolidayRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Counts leave days in a date range, skipping configured weekend days and PUBLIC holidays
 * (OPTIONAL holidays are counted as ordinary working days).
 */
@Component
public class WorkingDayCalculator {

    /**
     * @param workingDays   days charged to the employee (0.5 granularity)
     * @param excludedDates weekend and public-holiday dates inside the requested range, ascending
     */
    public record Result(BigDecimal workingDays, List<LocalDate> excludedDates) {
    }

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final HolidayRepository holidayRepository;
    private final LeaveProperties properties;

    public WorkingDayCalculator(HolidayRepository holidayRepository, LeaveProperties properties) {
        this.holidayRepository = holidayRepository;
        this.properties = properties;
    }

    public Result calculate(LocalDate from, LocalDate to, boolean halfDay) {
        Set<LocalDate> publicHolidays = new HashSet<>();
        for (Holiday h : holidayRepository.findByDateBetweenAndType(from, to, HolidayType.PUBLIC)) {
            publicHolidays.add(h.getDate());
        }
        return calculate(from, to, halfDay, properties.weekendDays(), publicHolidays);
    }

    /**
     * Pure calculation. A half day applies to a single-day range only: it is 0.5 when that day is a
     * working day and 0 otherwise. A range that is reversed yields zero days.
     */
    public static Result calculate(LocalDate from, LocalDate to, boolean halfDay, Set<DayOfWeek> weekend,
                                   Set<LocalDate> publicHolidays) {
        List<LocalDate> excluded = new ArrayList<>();
        int working = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (weekend.contains(d.getDayOfWeek()) || publicHolidays.contains(d)) {
                excluded.add(d);
            } else {
                working++;
            }
        }
        BigDecimal days = BigDecimal.valueOf(working).setScale(1);
        if (halfDay && from.equals(to)) {
            days = working > 0 ? HALF : BigDecimal.ZERO.setScale(1);
        }
        return new Result(days, List.copyOf(excluded));
    }
}
