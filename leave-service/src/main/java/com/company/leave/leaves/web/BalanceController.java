package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.AuthUser;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.company.leave.leaves.dto.MasterDtos.AdjustRequest;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.service.BalanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class BalanceController {

    private final BalanceService balanceService;
    private final EmployeeDirectoryClient directory;

    public BalanceController(BalanceService balanceService, EmployeeDirectoryClient directory) {
        this.balanceService = balanceService;
        this.directory = directory;
    }

    @GetMapping("/api/v1/leave-balances/me")
    public ApiResponse<List<BalanceDto>> mine(@RequestParam(required = false) Integer year) {
        AuthUser user = CurrentUser.get();
        return ApiResponse.ok(balanceService.forEmployee(user.id(), year));
    }

    @GetMapping("/api/v1/employees/{id}/leave-balances")
    public ApiResponse<List<BalanceDto>> ofEmployee(@PathVariable Long id,
                                                    @RequestParam(required = false) Integer year) {
        CurrentUser.requireRole("HR");
        directory.get(id);
        return ApiResponse.ok(balanceService.forEmployee(id, year));
    }

    @PostMapping("/api/v1/employees/{id}/leave-balances/adjust")
    public ApiResponse<BalanceDto> adjust(@PathVariable Long id, @Valid @RequestBody AdjustRequest request) {
        AuthUser hr = CurrentUser.requireRole("HR");
        directory.get(id);
        return ApiResponse.ok(balanceService.adjust(id, request, hr.id()));
    }
}
