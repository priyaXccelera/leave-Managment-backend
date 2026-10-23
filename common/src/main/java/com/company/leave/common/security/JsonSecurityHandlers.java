package com.company.leave.common.security;

import com.company.leave.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes JSON envelope bodies for 401 and 403 produced by the security filter chain.
 */
@Component
public class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid credentials");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    /** Shared writer, also usable by servlet filters that need to emit an error envelope. */
    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message, null));
    }
}
