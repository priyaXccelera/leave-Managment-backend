package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.dto.MasterDtos.LeaveTypeDto;
import com.company.leave.leaves.dto.MasterDtos.LeaveTypeRequest;
import com.company.leave.leaves.service.LeaveTypeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/leave-types")
public class LeaveTypeController {

    private final LeaveTypeService service;

    public LeaveTypeController(LeaveTypeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<LeaveTypeDto>> list() {
        return ApiResponse.ok(service.list(CurrentUser.get()));
    }

    @GetMapping("/{id}")
    public ApiResponse<LeaveTypeDto> get(@PathVariable Long id) {
        CurrentUser.get();
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LeaveTypeDto> create(@Valid @RequestBody LeaveTypeRequest request) {
        CurrentUser.requireRole("HR");
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LeaveTypeDto> update(@PathVariable Long id, @Valid @RequestBody LeaveTypeRequest request) {
        CurrentUser.requireRole("HR");
        return ApiResponse.ok(service.update(id, request));
    }
}
