package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.dto.MasterDtos.NotificationDto;
import com.company.leave.leaves.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<NotificationDto>> list(@RequestParam(required = false) Boolean unread,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer pageSize) {
        Long userId = CurrentUser.get().id();
        Page<NotificationDto> result = service.list(userId, Boolean.TRUE.equals(unread), page, pageSize);
        return ApiResponse.page(result.getContent(), result.getNumber() + 1, result.getSize(),
                result.getTotalElements());
    }

    @PatchMapping("/read-all")
    public ApiResponse<Map<String, Integer>> readAll() {
        return ApiResponse.ok(Map.of("updated", service.markAllRead(CurrentUser.get().id())));
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationDto> read(@PathVariable Long id) {
        return ApiResponse.ok(service.markRead(CurrentUser.get().id(), id));
    }
}
