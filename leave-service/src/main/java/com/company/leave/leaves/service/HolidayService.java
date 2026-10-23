package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Holiday;
import com.company.leave.leaves.dto.MasterDtos.HolidayDto;
import com.company.leave.leaves.dto.MasterDtos.HolidayRequest;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.HolidayRepository;
import com.company.leave.leaves.web.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class HolidayService {

    static final Set<String> SORT_FIELDS = Set.of("id", "date", "name", "type");
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final HolidayRepository holidays;

    public HolidayService(HolidayRepository holidays) {
        this.holidays = holidays;
    }

    /** Holidays of the year (default: current), by date unless sorted otherwise; paged in the database. */
    @Transactional(readOnly = true)
    public Page<HolidayDto> list(Integer year, Integer page, Integer pageSize, String sort) {
        int y = year == null ? BalanceService.currentYear() : year;
        Pageable pageable = PageRequests.of(page, pageSize == null ? DEFAULT_PAGE_SIZE : pageSize, sort,
                SORT_FIELDS, "date,asc");
        return holidays.findByYear(y, pageable).map(MasterMapper::toDto);
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
