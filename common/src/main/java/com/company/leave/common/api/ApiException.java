package com.company.leave.common.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Business exception translated to the JSON error envelope by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<ApiResponse.FieldError> details;

    public ApiException(HttpStatus status, String code, String message, List<ApiResponse.FieldError> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : details;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException validation(String message, List<ApiResponse.FieldError> details) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, details);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ApiResponse.FieldError> getDetails() {
        return details;
    }
}
