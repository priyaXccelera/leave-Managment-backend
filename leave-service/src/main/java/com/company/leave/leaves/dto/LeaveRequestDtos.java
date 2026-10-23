package com.company.leave.leaves.dto;

import com.company.leave.leaves.domain.Enums.HalfDaySession;
import com.company.leave.leaves.domain.Enums.HistoryAction;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.Enums.Stage;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request/response DTOs of the leave request workflow.
 */
public final class LeaveRequestDtos {

    private LeaveRequestDtos() {
    }

    public record PreviewRequest(@NotNull Long leaveTypeId, @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                                 boolean halfDay, HalfDaySession halfDaySession) {
    }

    /** The JSON member is literally {@code isValid}. */
    public record PreviewResponse(BigDecimal workingDays, List<LocalDate> excludedDates,
                                  BigDecimal availableBalance,
                                  @JsonProperty("isValid") boolean isValid, List<String> warnings) {
    }

    public record ApplyRequest(@NotNull Long leaveTypeId, @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                               boolean halfDay, HalfDaySession halfDaySession,
                               @NotBlank @Size(max = 1000) String reason,
                               @Size(max = 30) String contactNumber) {
    }

    /** {@code action} is APPROVE or REJECT (case-insensitive). */
    public record ActionRequest(@NotBlank String action, @Size(max = 1000) String comment) {
    }

    public record CancelRequest(@Size(max = 1000) String comment) {
    }

    public record EmployeeRef(Long id, String employeeCode, String name, String department) {
    }

    public record LeaveTypeRef(Long id, String name, String code) {
    }

    public record HistoryEntry(HistoryAction action, String actorName, String actorRole, String comment,
                               Instant timestamp) {
    }

    public record LeaveRequestDto(Long id, EmployeeRef employee, LeaveTypeRef leaveType, LocalDate fromDate,
                                  LocalDate toDate, boolean halfDay, HalfDaySession halfDaySession,
                                  BigDecimal workingDays, String reason, String contactNumber,
                                  String attachmentUrl, RequestStatus status, Stage currentStage,
                                  Instant appliedOn, Instant finalDecisionDate, List<HistoryEntry> history) {
    }
}
