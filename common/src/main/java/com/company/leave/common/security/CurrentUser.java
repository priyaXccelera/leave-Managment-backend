package com.company.leave.common.security;

import com.company.leave.common.api.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

/**
 * Static accessor for the authenticated user of the current request.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw ApiException.unauthorized("Authentication is required");
        }
        return user;
    }

    public static AuthUser requireRole(String... roles) {
        AuthUser user = get();
        if (Arrays.stream(roles).noneMatch(r -> r.equals(user.role()))) {
            throw ApiException.forbidden("You do not have permission to perform this action");
        }
        return user;
    }
}
