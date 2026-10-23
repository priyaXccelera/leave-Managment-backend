package com.company.leave.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Uniform response envelope. Null members are omitted, so {@code meta} only appears for lists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, Meta meta, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<List<T>> page(List<T> items, int page, int pageSize, long total) {
        return new ApiResponse<>(true, items, new Meta(page, pageSize, total), null);
    }

    public static ApiResponse<Void> error(String code, String message, List<FieldError> details) {
        return new ApiResponse<>(false, null, null, new ErrorBody(code, message, details == null ? List.of() : details));
    }

    public record Meta(int page, int pageSize, long total) {
    }

    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    public record FieldError(String field, String message) {
    }
}
