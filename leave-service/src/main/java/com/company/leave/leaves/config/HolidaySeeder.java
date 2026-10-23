package com.company.leave.leaves.config;

import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Holiday;
import com.company.leave.leaves.repo.HolidayRepository;
import com.company.leave.leaves.service.BalanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds sample holidays for the current year on first start. Skipped when the year already has holidays or when
 * {@code leave.seed-holidays=false}.
 */
@Component
@ConditionalOnProperty(name = "leave.seed-holidays", havingValue = "true", matchIfMissing = true)
public class HolidaySeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(HolidaySeeder.class);

    private final HolidayRepository holidays;

    public HolidaySeeder(HolidayRepository holidays) {
        this.holidays = holidays;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int year = BalanceService.currentYear();
        if (holidays.existsByYear(year)) {
            return;
        }
        List<Holiday> seed = new ArrayList<>();
        seed.add(holiday(year, 1, 1, "New Year's Day", HolidayType.PUBLIC));
        seed.add(holiday(year, 1, 26, "Republic Day", HolidayType.PUBLIC));
        seed.add(holiday(year, 8, 15, "Independence Day", HolidayType.PUBLIC));
        seed.add(holiday(year, 10, 2, "Gandhi Jayanti", HolidayType.PUBLIC));
        seed.add(holiday(year, 12, 25, "Christmas", HolidayType.PUBLIC));
        seed.add(holiday(year, 3, 14, "Holi", HolidayType.OPTIONAL));
        seed.add(holiday(year, 11, 1, "Diwali", HolidayType.OPTIONAL));
        holidays.saveAll(seed);
        LOG.info("Seeded {} sample holidays for {}", seed.size(), year);
    }

    private static Holiday holiday(int year, int month, int day, String name, HolidayType type) {
        Holiday h = new Holiday();
        h.setDate(LocalDate.of(year, month, day));
        h.setName(name);
        h.setType(type);
        h.setYear(year);
        return h;
    }
}
