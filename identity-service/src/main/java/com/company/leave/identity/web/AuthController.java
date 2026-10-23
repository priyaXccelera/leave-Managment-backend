package com.company.leave.identity.web;

import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.identity.dto.AuthDtos.ChangePasswordRequest;
import com.company.leave.identity.dto.AuthDtos.LoginRequest;
import com.company.leave.identity.dto.AuthDtos.LogoutRequest;
import com.company.leave.identity.dto.AuthDtos.MessageResponse;
import com.company.leave.identity.dto.AuthDtos.RefreshRequest;
import com.company.leave.identity.dto.AuthDtos.TokenResponse;
import com.company.leave.identity.dto.AuthDtos.UserDto;
import com.company.leave.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.identifier(), request.password()));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<MessageResponse> logout(@Valid @RequestBody(required = false) LogoutRequest request) {
        authService.logout(CurrentUser.get().id(), request == null ? null : request.refreshToken());
        return ApiResponse.ok(new MessageResponse("Logged out"));
    }

    @PostMapping("/change-password")
    public ApiResponse<MessageResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.get().id(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok(new MessageResponse("Password changed"));
    }

    @GetMapping("/me")
    public ApiResponse<UserDto> me() {
        return ApiResponse.ok(authService.me(CurrentUser.get().id()));
    }
}
