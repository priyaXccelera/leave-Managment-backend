package com.company.leave.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    /** Used for both create (password required, checked in the service) and full update (password optional). */
    public record EmployeeRequest(
            @NotBlank(message = "employeeCode is required") @Size(max = 30) String employeeCode,
            @NotBlank(message = "name is required") @Size(max = 150) String name,
            @NotBlank(message = "email is required") @Email(message = "email must be a valid address") @Size(max = 190) String email,
            @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters") String password,
            @NotBlank(message = "role is required")
            @Pattern(regexp = "(?i)EMPLOYEE|MANAGER|HR", message = "role must be one of EMPLOYEE, MANAGER, HR") String role,
            @NotBlank(message = "department is required") @Size(max = 100) String department,
            @Size(max = 100) String designation,
            @NotNull(message = "dateOfJoining is required") LocalDate dateOfJoining,
            Long managerId) {
    }

    public record StatusRequest(@NotNull(message = "active is required") Boolean active) {
    }

    public record EmployeeResponse(Long id, String employeeCode, String name, String email, String role,
                                   String department, String designation, LocalDate dateOfJoining, Long managerId,
                                   AuthDtos.ManagerRef manager, boolean active, Instant createdAt) {
    }

    public record InternalEmployee(Long id, String employeeCode, String name, String email, String role,
                                   String department, Long managerId, boolean active) {
    }
}
