package com.company.leave.common.security;

/**
 * Authenticated principal reconstructed from the JWT claims.
 */
public record AuthUser(Long id, String employeeCode, String name, String email, String role, String department) {

    public boolean isHr() {
        return "HR".equals(role);
    }

    public boolean isManager() {
        return "MANAGER".equals(role);
    }
}
