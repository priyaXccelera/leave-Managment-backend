package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.dto.MasterDtos.HolidayDto;
import com.company.leave.leaves.dto.MasterDtos.HolidayRequest;
import com.company.leave.leaves.service.HolidayService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/holidays")
public class HolidayController {

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<HolidayDto>> list(@RequestParam(required = false) Integer year,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer pageSize,
                                              @RequestParam(required = false) String sort) {
        CurrentUser.get();
        Page<HolidayDto> result = service.list(year, page, pageSize, sort);
        return ApiResponse.page(result.getContent(), result.getNumber() + 1, result.getSize(),
                result.getTotalElements());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<HolidayDto> create(@Valid @RequestBody HolidayRequest request) {
        CurrentUser.requireRole("HR");
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<HolidayDto> update(@PathVariable Long id, @Valid @RequestBody HolidayRequest request) {
        CurrentUser.requireRole("HR");
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        CurrentUser.requireRole("HR");
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
