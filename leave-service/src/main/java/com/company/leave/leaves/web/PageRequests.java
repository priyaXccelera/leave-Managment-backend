package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

/**
 * Normalises the public paging parameters: {@code page} is 1-based (&lt; 1 is rejected), {@code pageSize}
 * defaults to 20 and is clamped to 100 (&lt; 1 is rejected), {@code sort} is {@code field[,asc|desc]} restricted
 * to a whitelist. A stable {@code id} tie-breaker is appended so pages never overlap.
 */
public final class PageRequests {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    public static Pageable of(Integer page, Integer pageSize, String sort, Set<String> allowedFields,
                              String defaultSort) {
        int p = page == null ? 1 : page;
        if (p < 1) {
            throw invalid("page", "page must be >= 1");
        }
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (size < 1) {
            throw invalid("pageSize", "pageSize must be >= 1");
        }
        size = Math.min(size, MAX_PAGE_SIZE);
        Sort order = parseSort(sort == null || sort.isBlank() ? defaultSort : sort, allowedFields);
        return PageRequest.of(p - 1, size, order);
    }

    private static Sort parseSort(String sort, Set<String> allowedFields) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "id");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!allowedFields.contains(field)) {
            throw invalid("sort", "Unsupported sort field '" + field + "'; allowed: "
                    + String.join(", ", allowedFields.stream().sorted().toList()));
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1 && !parts[1].isBlank()) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElseThrow(() -> invalid("sort", "Sort direction must be asc or desc"));
        }
        if (parts.length > 2) {
            throw invalid("sort", "Sort must be of the form field,asc|desc");
        }
        Sort result = Sort.by(direction, field);
        return "id".equals(field) ? result : result.and(Sort.by(direction, "id"));
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.validation(message, List.of(new FieldError(field, message)));
    }
}
