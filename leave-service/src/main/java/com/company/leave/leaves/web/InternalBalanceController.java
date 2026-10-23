package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.dto.MasterDtos.InitBalancesRequest;
import com.company.leave.leaves.service.BalanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service endpoint guarded by the X-Internal-Key filter; not routed by the gateway. */
@RestController
@RequestMapping("/internal/balances")
public class InternalBalanceController {

    private final BalanceService balanceService;

    public InternalBalanceController(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @PostMapping("/init")
    public ApiResponse<List<BalanceDto>> init(@Valid @RequestBody InitBalancesRequest request) {
        return ApiResponse.ok(balanceService.initForEmployee(request.employeeId(), request.dateOfJoining()));
    }
}
