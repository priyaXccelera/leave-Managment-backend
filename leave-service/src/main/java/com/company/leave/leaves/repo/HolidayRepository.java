package com.company.leave.leaves.repo;

import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Holiday;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByYearOrderByDate(int year);

    Page<Holiday> findByYear(int year, Pageable pageable);

    List<Holiday> findByDateGreaterThanEqualOrderByDate(LocalDate from, Pageable pageable);

    List<Holiday> findByDateBetween(LocalDate from, LocalDate to);

    List<Holiday> findByDateBetweenOrderByDate(LocalDate from, LocalDate to);

    List<Holiday> findByDateBetweenAndType(LocalDate from, LocalDate to, HolidayType type);

    boolean existsByDate(LocalDate date);

    boolean existsByDateAndIdNot(LocalDate date, Long id);

    boolean existsByYear(int year);
}
