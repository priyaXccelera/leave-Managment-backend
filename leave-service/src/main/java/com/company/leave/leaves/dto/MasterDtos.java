package com.company.leave.leaves.dto;

import com.company.leave.leaves.domain.Enums.HolidayType;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs of the master data (leave types, holidays), balances, notifications and aggregate views.
 */
public final class MasterDtos {

    private MasterDtos() {
    }

    /**
     * Create/update payload. Optional members fall back to defaults when omitted:
     * quota 0, unlimited false, paid true, carryForward false, maxCarry 0, halfDay true, attachment false,
     * minNotice 0, active true.
     */
    public record LeaveTypeRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 20) String code,
            @DecimalMin("0") BigDecimal yearlyQuota,
            Boolean unlimited,
            Boolean paid,
            Boolean carryForwardAllowed,
            @DecimalMin("0") BigDecimal maxCarryForwardDays,
            Boolean halfDayAllowed,
            Boolean attachmentRequired,
            @Min(0) Integer attachmentRequiredAfterDays,
            @Min(0) Integer minNoticeDays,
            Boolean active) {
    }

    public record LeaveTypeDto(Long id, String name, String code, BigDecimal yearlyQuota, boolean unlimited,
                               boolean paid, boolean carryForwardAllowed, BigDecimal maxCarryForwardDays,
                               boolean halfDayAllowed, boolean attachmentRequired,
                               Integer attachmentRequiredAfterDays, int minNoticeDays, boolean active) {
    }

    /** {@code type} defaults to PUBLIC when omitted. */
    public record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 150) String name,
                                 HolidayType type) {
    }

    public record HolidayDto(Long id, LocalDate date, String name, HolidayType type, int year) {
    }

    /** {@code code} duplicates {@code leaveTypeCode} for client convenience. */
    public record BalanceDto(Long leaveTypeId, String leaveTypeName, String leaveTypeCode, String code, int year,
                             BigDecimal total, BigDecimal used, BigDecimal pending, BigDecimal available,
                             BigDecimal carriedForward, boolean unlimited) {
    }

    /** {@code year} defaults to the current year; {@code days} may be negative. */
    public record AdjustRequest(@NotNull Long leaveTypeId, Integer year, @NotNull BigDecimal days,
                                @NotBlank @Size(max = 500) String reason) {
    }

    public record InitBalancesRequest(@NotNull Long employeeId, @NotNull LocalDate dateOfJoining) {
    }

    public record NotificationDto(Long id, String message, Long requestId, boolean read, Instant createdAt) {
    }

    public record SummaryRow(String key, String label, long requests, BigDecimal days, long approved,
                             long pending, long rejected) {
    }

    public record OnLeaveDto(Long requestId, Long employeeId, String employeeCode, String employeeName,
                             String department, String leaveType, LocalDate fromDate, LocalDate toDate,
                             boolean halfDay, BigDecimal workingDays) {
    }

    public record DashboardDto(String role, long pendingApprovals, List<BalanceDto> balances,
                               List<LeaveRequestDtos.LeaveRequestDto> upcomingLeaves,
                               List<HolidayDto> upcomingHolidays, long unreadNotifications,
                               List<OnLeaveDto> onLeaveToday) {
    }

    public record CalendarEntry(Long requestId, Long employeeId, String employeeName, String department,
                                String leaveType, LocalDate fromDate, LocalDate toDate, RequestStatus status,
                                BigDecimal workingDays) {
    }
}
