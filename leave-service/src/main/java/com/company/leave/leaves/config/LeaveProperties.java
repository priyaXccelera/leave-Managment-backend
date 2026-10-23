package com.company.leave.leaves.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.DayOfWeek;
import java.util.Set;

/**
 * Typed view of the {@code leave.*} settings (all sourced from environment variables, see application.yml).
 */
@Validated
@ConfigurationProperties("leave")
public record LeaveProperties(
        @NotEmpty Set<DayOfWeek> weekendDays,
        @Min(0) int backdateDays,
        @Min(0) int escalationDays,
        @NotBlank String uploadDir,
        @Min(1) int maxUploadMb,
        @NotBlank String identityUrl,
        @NotBlank String internalApiKey,
        @NotBlank String jwtSecret) {
}
