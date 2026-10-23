package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.dto.MasterDtos.OnLeaveDto;
import com.company.leave.leaves.dto.MasterDtos.SummaryRow;
import com.company.leave.leaves.service.BalanceService;
import com.company.leave.leaves.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** HR-only reports. */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<List<SummaryRow>> summary(
            @RequestParam(defaultValue = "department") String groupBy,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CurrentUser.requireRole("HR");
        return ApiResponse.ok(service.summary(groupBy, year, from, to));
    }

    @GetMapping("/on-leave")
    public ApiResponse<List<OnLeaveDto>> onLeave(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CurrentUser.requireRole("HR");
        return ApiResponse.ok(service.onLeave(from, to));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Integer year,
                                         @RequestParam(required = false) Long leaveTypeId,
                                         @RequestParam(required = false) String status) {
        CurrentUser.requireRole("HR");
        int y = year == null ? BalanceService.currentYear() : year;
        String csv = service.exportCsv(y, leaveTypeId, status);
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("leave-report-" + y + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
