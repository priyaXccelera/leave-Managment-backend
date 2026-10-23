package com.company.leave.identity.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.AuthUser;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.identity.dto.EmployeeDtos.EmployeeRequest;
import com.company.leave.identity.dto.EmployeeDtos.EmployeeResponse;
import com.company.leave.identity.dto.EmployeeDtos.StatusRequest;
import com.company.leave.identity.service.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HR-only employee administration. */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private static final String HR = "HR";

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    public ApiResponse<List<EmployeeResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String q) {
        CurrentUser.requireRole(HR);
        return employeeService.list(page, pageSize, sort, role, department, active, q);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EmployeeResponse> create(@Valid @RequestBody EmployeeRequest request) {
        CurrentUser.requireRole(HR);
        return ApiResponse.ok(employeeService.create(request));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<EmployeeResponse> get(@PathVariable Long id) {
        CurrentUser.requireRole(HR);
        return ApiResponse.ok(employeeService.get(id));
    }

    @PutMapping("/{id:\\d+}")
    public ApiResponse<EmployeeResponse> update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        CurrentUser.requireRole(HR);
        return ApiResponse.ok(employeeService.update(id, request));
    }

    @PatchMapping("/{id:\\d+}/status")
    public ApiResponse<EmployeeResponse> setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        AuthUser caller = CurrentUser.requireRole(HR);
        return ApiResponse.ok(employeeService.setActive(id, request.active(), caller.id()));
    }
}
