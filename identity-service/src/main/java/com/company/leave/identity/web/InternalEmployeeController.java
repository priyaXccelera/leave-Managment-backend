package com.company.leave.identity.web;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse;
import com.company.leave.identity.dto.EmployeeDtos.InternalEmployee;
import com.company.leave.identity.service.EmployeeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service API; guarded by InternalKeyFilter, never routed through the gateway. */
@RestController
@RequestMapping("/internal/employees")
public class InternalEmployeeController {

    private final EmployeeService employeeService;

    public InternalEmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping("/hr")
    public ApiResponse<List<InternalEmployee>> hr() {
        return ApiResponse.ok(employeeService.internalHr());
    }

    @GetMapping("/by-manager/{managerId:\\d+}")
    public ApiResponse<List<InternalEmployee>> byManager(@PathVariable Long managerId) {
        return ApiResponse.ok(employeeService.internalByManager(managerId));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<InternalEmployee> byId(@PathVariable Long id) {
        return ApiResponse.ok(employeeService.internalGet(id));
    }

    @GetMapping
    public ApiResponse<List<InternalEmployee>> search(@RequestParam(required = false) String ids,
                                                      @RequestParam(required = false) String department) {
        if (ids != null) {
            return ApiResponse.ok(employeeService.internalByIds(ids));
        }
        if (department != null && !department.isBlank()) {
            return ApiResponse.ok(employeeService.internalByDepartment(department));
        }
        throw ApiException.badRequest("VALIDATION_ERROR", "Either 'ids' or 'department' is required");
    }
}
