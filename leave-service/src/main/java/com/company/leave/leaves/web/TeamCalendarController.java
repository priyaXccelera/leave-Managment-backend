package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.dto.MasterDtos.CalendarEntry;
import com.company.leave.leaves.service.LeaveRequestQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/team")
public class TeamCalendarController {

    private final LeaveRequestQueryService queries;

    public TeamCalendarController(LeaveRequestQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/calendar")
    public ApiResponse<List<CalendarEntry>> calendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String department) {
        return ApiResponse.ok(queries.calendar(CurrentUser.requireRole("MANAGER", "HR"), from, to, department));
    }
}
