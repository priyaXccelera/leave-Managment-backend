package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.dto.MasterDtos.DashboardDto;
import com.company.leave.leaves.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DashboardDto> dashboard() {
        return ApiResponse.ok(service.build(CurrentUser.get()));
    }
}
