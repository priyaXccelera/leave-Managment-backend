package com.company.leave.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "identifier is required") @Size(max = 190) String identifier,
            @NotBlank(message = "password is required") @Size(max = 200) String password) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken is required") @Size(max = 200) String refreshToken) {
    }

    public record LogoutRequest(@Size(max = 200) String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "currentPassword is required") @Size(max = 200) String currentPassword,
            @NotBlank(message = "newPassword is required")
            @Size(min = 8, max = 72, message = "newPassword must be between 8 and 72 characters") String newPassword) {
    }

    public record ManagerRef(Long id, String employeeCode, String name) {
    }

    public record UserDto(Long id, String employeeCode, String name, String email, String role, String department,
                          String designation, LocalDate dateOfJoining, ManagerRef manager, boolean active) {
    }

    /** {@code token} duplicates {@code accessToken} for clients that expect either name. */
    public record TokenResponse(String token, String accessToken, String refreshToken, long expiresIn, UserDto user) {
    }

    public record MessageResponse(String message) {
    }
}
