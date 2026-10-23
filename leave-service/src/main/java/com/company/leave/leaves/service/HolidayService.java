package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Holiday;
import com.company.leave.leaves.dto.MasterDtos.HolidayDto;
import com.company.leave.leaves.dto.MasterDtos.HolidayRequest;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.HolidayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HolidayService {

    private final HolidayRepository holidays;

    public HolidayService(HolidayRepository holidays) {
        this.holidays = holidays;
    }

    /** Holidays of the year (default: current), by date. */
    @Transactional(readOnly = true)
    public List<HolidayDto> list(Integer year) {
        int y = year == null ? BalanceService.currentYear() : year;
        return holidays.findByYearOrderByDate(y).stream().map(MasterMapper::toDto).toList();
    }

    /** @throws ApiException 409 DUPLICATE_HOLIDAY when a holiday already exists on that date */
    @Transactional
    public HolidayDto create(HolidayRequest request) {
        if (holidays.existsByDate(request.date())) {
            throw duplicate(request);
        }
        Holiday h = new Holiday();
        apply(h, request);
        return MasterMapper.toDto(holidays.save(h));
    }

    /** @throws ApiException 404 when unknown, 409 DUPLICATE_HOLIDAY when another holiday has the date */
    @Transactional
    public HolidayDto update(Long id, HolidayRequest request) {
        Holiday h = find(id);
        if (holidays.existsByDateAndIdNot(request.date(), id)) {
            throw duplicate(request);
        }
        apply(h, request);
        return MasterMapper.toDto(holidays.save(h));
    }

    /** @throws ApiException 404 when unknown */
    @Transactional
    public void delete(Long id) {
        holidays.delete(find(id));
    }

    private Holiday find(Long id) {
        return holidays.findById(id).orElseThrow(() -> ApiException.notFound("Holiday not found"));
    }

    private static ApiException duplicate(HolidayRequest request) {
        return ApiException.conflict("DUPLICATE_HOLIDAY", "A holiday already exists on " + request.date());
    }

    private static void apply(Holiday h, HolidayRequest r) {
        h.setDate(r.date());
        h.setName(r.name().trim());
        h.setType(r.type() == null ? HolidayType.PUBLIC : r.type());
        h.setYear(r.date().getYear());
    }
}
