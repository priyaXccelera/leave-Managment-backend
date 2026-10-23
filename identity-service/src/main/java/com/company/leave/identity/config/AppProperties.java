package com.company.leave.identity.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding of the environment-driven settings. Missing required values abort startup.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank(message = "JWT_SECRET is required") String jwtSecret,
        @Min(value = 1, message = "ACCESS_TOKEN_TTL_SECONDS must be >= 1") long accessTokenTtlSeconds,
        @Min(value = 1, message = "REFRESH_TOKEN_TTL_DAYS must be >= 1") long refreshTokenTtlDays,
        @NotBlank(message = "INTERNAL_API_KEY is required") String internalApiKey,
        @NotBlank(message = "LEAVE_SERVICE_URL is required") String leaveServiceUrl,
        @NotBlank(message = "SEED_PASSWORD must not be blank") String seedPassword,
        @Min(value = 1, message = "LOGIN_RATE_LIMIT must be >= 1") int loginRateLimit,
        @Min(value = 1, message = "LOGIN_RATE_WINDOW_SECONDS must be >= 1") int loginRateWindowSeconds) {
}
