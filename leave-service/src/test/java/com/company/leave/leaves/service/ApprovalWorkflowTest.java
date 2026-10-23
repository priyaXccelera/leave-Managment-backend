package com.company.leave.leaves.service;

import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.WorkflowTestSupport;
import com.company.leave.leaves.domain.Enums.HalfDaySession;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.Enums.Stage;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.LeaveRequestDtos.ActionRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.ApplyRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.CancelRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.LeaveRequestDtos.PreviewRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.PreviewResponse;
import com.company.leave.leaves.config.LeaveProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ApprovalWorkflowTest extends WorkflowTestSupport {

    private static final ActionRequest APPROVE = new ActionRequest("APPROVE", "ok");

    @Autowired
    private LeaveProperties properties;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void employeeToManagerToHrApprovalMovesBalanceAndNotifiesTheRightPeople() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        AuthUser hr2 = register("HR", null);
        registerHrUsers(hr, hr2);
        LocalDate from = nextMonday(14);

        LeaveRequestDto applied = apply(emp, "CL", from, from.plusDays(1));
        assertEquals(RequestStatus.PENDING_MANAGER, applied.status());
        assertEquals(Stage.MANAGER, applied.currentStage());
        assertEquals(d("2.0"), applied.workingDays());
        assertEquals("CL", applied.leaveType().code());
        assertEquals(emp.id(), applied.employee().id());
        assertNull(applied.attachmentUrl());
        assertEquals(d("2.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(1, notificationCount(mgr.id()));
        assertEquals(0, notificationCount(hr.id()));

        LeaveRequestDto afterManager = approvalService.managerAction(mgr, applied.id(), APPROVE);
        assertEquals(RequestStatus.PENDING_HR, afterManager.status());
        assertEquals(Stage.HR, afterManager.currentStage());
        assertNull(afterManager.finalDecisionDate());
        assertEquals(d("2.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(1, notificationCount(hr.id()));
        assertEquals(1, notificationCount(hr2.id()));
        assertEquals(0, notificationCount(emp.id()));

        LeaveRequestDto approved = approvalService.hrAction(hr, applied.id(), APPROVE);
        assertEquals(RequestStatus.APPROVED, approved.status());
        assertEquals(Stage.NONE, approved.currentStage());
        assertNotNull(approved.finalDecisionDate());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(d("2.0"), balance(emp.id(), "CL", from.getYear()).used());
        assertEquals(d("10.0"), balance(emp.id(), "CL", from.getYear()).available());
        assertEquals(1, notificationCount(emp.id()));
        assertEquals(2, notificationCount(mgr.id()));
        assertEquals(List.of("APPLIED", "MANAGER_APPROVED", "HR_APPROVED"), historyActions(approved));
        assertEquals("HR", approved.history().get(2).actorRole());
    }

    @Test
    void managerRejectNeedsCommentAndReleasesPending() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);
        LeaveRequestDto applied = apply(emp, "CL", from, from);

        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                () -> approvalService.managerAction(mgr, applied.id(), new ActionRequest("REJECT", null)));
        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                () -> approvalService.managerAction(mgr, applied.id(), new ActionRequest("REJECT", "   ")));
        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                () -> approvalService.managerAction(mgr, applied.id(), new ActionRequest("MAYBE", "x")));
        assertEquals(d("1.0"), balance(emp.id(), "CL", from.getYear()).pending());

        LeaveRequestDto rejected = approvalService.managerAction(mgr, applied.id(),
                new ActionRequest("reject", "Release deadline"));
        assertEquals(RequestStatus.REJECTED_BY_MANAGER, rejected.status());
        assertEquals(Stage.NONE, rejected.currentStage());
        assertNotNull(rejected.finalDecisionDate());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).used());
        assertEquals(1, notificationCount(emp.id()));
        assertEquals("Release deadline", rejected.history().get(1).comment());
        assertEquals(List.of("APPLIED", "MANAGER_REJECTED"), historyActions(rejected));
    }

    @Test
    void hrRejectWithCommentReleasesPending() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate from = nextMonday(14);
        LeaveRequestDto applied = apply(emp, "CL", from, from.plusDays(2));
        approvalService.managerAction(mgr, applied.id(), APPROVE);

        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                () -> approvalService.hrAction(hr, applied.id(), new ActionRequest("REJECT", "")));
        LeaveRequestDto rejected = approvalService.hrAction(hr, applied.id(),
                new ActionRequest("REJECT", "Blackout period"));
        assertEquals(RequestStatus.REJECTED_BY_HR, rejected.status());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(d("12.0"), balance(emp.id(), "CL", from.getYear()).available());
        assertEquals(1, notificationCount(emp.id()));
        assertEquals(2, notificationCount(mgr.id()));
        assertEquals(List.of("APPLIED", "MANAGER_APPROVED", "HR_REJECTED"), historyActions(rejected));
    }

    @Test
    void managerAndHrAndEmployeeWithoutManagerStartAtHrStage() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser hr = register("HR", null);
        AuthUser hr2 = register("HR", null);
        AuthUser loner = register("EMPLOYEE", null);
        registerHrUsers(hr, hr2);
        LocalDate from = nextMonday(14);

        LeaveRequestDto managerLeave = apply(mgr, "CL", from, from);
        assertEquals(RequestStatus.PENDING_HR, managerLeave.status());
        assertEquals(Stage.HR, managerLeave.currentStage());
        assertEquals(1, notificationCount(hr.id()));
        assertEquals(1, notificationCount(hr2.id()));
        assertEquals(0, notificationCount(mgr.id()));

        LeaveRequestDto lonerLeave = apply(loner, "CL", from, from);
        assertEquals(RequestStatus.PENDING_HR, lonerLeave.status());
        assertEquals(2, notificationCount(hr.id()));

        LeaveRequestDto hrLeave = apply(hr, "CL", from, from);
        assertEquals(RequestStatus.PENDING_HR, hrLeave.status());
        assertEquals(2, notificationCount(hr.id()), "applicant HR is not notified about their own request");
        assertEquals(3, notificationCount(hr2.id()));
    }

    @Test
    void hrCannotApproveOwnRequestButAnotherHrCan() {
        AuthUser hr = register("HR", null);
        AuthUser hr2 = register("HR", null);
        registerHrUsers(hr, hr2);
        LocalDate from = nextMonday(14);
        LeaveRequestDto own = apply(hr, "CL", from, from);

        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN", () -> approvalService.hrAction(hr, own.id(), APPROVE));
        assertEquals(RequestStatus.PENDING_HR, requestRepo.findById(own.id()).orElseThrow().getStatus());
        LeaveRequestDto approved = approvalService.hrAction(hr2, own.id(), APPROVE);
        assertEquals(RequestStatus.APPROVED, approved.status());
        assertEquals(d("1.0"), balance(hr.id(), "CL", from.getYear()).used());
    }

    @Test
    void cancelPendingAtBothStagesReleasesBalance() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate from = nextMonday(14);

        LeaveRequestDto atManager = apply(emp, "CL", from, from.plusDays(1));
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN",
                () -> requestService.cancel(register("EMPLOYEE", mgr.id()), atManager.id(), null));
        LeaveRequestDto cancelled = requestService.cancel(emp, atManager.id(), new CancelRequest("Plans changed"));
        assertEquals(RequestStatus.CANCELLED, cancelled.status());
        assertEquals(Stage.NONE, cancelled.currentStage());
        assertNotNull(cancelled.finalDecisionDate());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(2, notificationCount(mgr.id()), "manager was told about apply and cancel");
        assertEquals("Plans changed", cancelled.history().get(1).comment());

        LeaveRequestDto atHr = apply(emp, "CL", from, from.plusDays(1));
        approvalService.managerAction(mgr, atHr.id(), APPROVE);
        LeaveRequestDto cancelledAtHr = requestService.cancel(emp, atHr.id(), null);
        assertEquals(RequestStatus.CANCELLED, cancelledAtHr.status());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).pending());
        assertEquals(2, notificationCount(hr.id()), "HR was told about the manager approval and the cancel");
        assertEquals(List.of("APPLIED", "MANAGER_APPROVED", "CANCELLED"), historyActions(cancelledAtHr));

        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> requestService.cancel(emp, atHr.id(), null));
    }

    @Test
    void cancelApprovedBeforeStartRestoresUsedButNotOnOrAfterStart() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate from = nextMonday(14);

        LeaveRequestDto applied = apply(emp, "CL", from, from.plusDays(1));
        approvalService.managerAction(mgr, applied.id(), APPROVE);
        approvalService.hrAction(hr, applied.id(), APPROVE);
        assertEquals(d("2.0"), balance(emp.id(), "CL", from.getYear()).used());

        LeaveRequestDto cancelled = requestService.cancel(emp, applied.id(), null);
        assertEquals(RequestStatus.CANCELLED, cancelled.status());
        assertEquals(d("0.0"), balance(emp.id(), "CL", from.getYear()).used());
        assertEquals(d("12.0"), balance(emp.id(), "CL", from.getYear()).available());

        LeaveRequest started = insertRequest(emp, mgr.id(), "CL", today(), today(), RequestStatus.APPROVED);
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> requestService.cancel(emp, started.getId(), null));
        LeaveRequest running = insertRequest(emp, mgr.id(), "CL", today().minusDays(1), today().plusDays(1),
                RequestStatus.APPROVED);
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> requestService.cancel(emp, running.getId(), null));

        LeaveRequest rejected = insertRequest(emp, mgr.id(), "CL", from, from, RequestStatus.REJECTED_BY_HR);
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> requestService.cancel(emp, rejected.getId(), null));
        assertApi(HttpStatus.NOT_FOUND, "NOT_FOUND", () -> requestService.cancel(emp, 987_654_321L, null));
    }

    @Test
    void invalidTransitionsAndWrongActors() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser otherMgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate from = nextMonday(14);
        LeaveRequestDto applied = apply(emp, "CL", from, from);

        // HR cannot jump the manager stage
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> approvalService.hrAction(hr, applied.id(), APPROVE));
        // a manager who does not manage the employee, an employee and HR are not allowed at manager stage
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN",
                () -> approvalService.managerAction(otherMgr, applied.id(), APPROVE));
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN", () -> approvalService.managerAction(emp, applied.id(), APPROVE));
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN", () -> approvalService.managerAction(hr, applied.id(), APPROVE));
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN", () -> approvalService.hrAction(mgr, applied.id(), APPROVE));
        assertApi(HttpStatus.NOT_FOUND, "NOT_FOUND", () -> approvalService.managerAction(mgr, 987_654_321L, APPROVE));

        approvalService.managerAction(mgr, applied.id(), APPROVE);
        // acting twice / at the wrong stage
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                () -> approvalService.managerAction(mgr, applied.id(), APPROVE));
        approvalService.hrAction(hr, applied.id(), APPROVE);
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> approvalService.hrAction(hr, applied.id(), APPROVE));
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                () -> approvalService.hrAction(hr, applied.id(), new ActionRequest("REJECT", "late")));
        assertEquals(d("1.0"), balance(emp.id(), "CL", from.getYear()).used());
    }

    @Test
    void managerOfSnapshotIsVerifiedAgainstTheDirectory() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser newMgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        registerHrUsers(register("HR", null));
        LeaveRequestDto applied = apply(emp, "CL", nextMonday(14), nextMonday(14));
        // the employee has been re-assigned to another manager since applying
        when(directory.get(emp.id())).thenReturn(info(emp, newMgr.id()));
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN", () -> approvalService.managerAction(mgr, applied.id(), APPROVE));
    }

    @Test
    void escalationMovesOnlyStaleManagerRequestsAndIsIdempotent() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        AuthUser hr = register("HR", null);
        registerHrUsers(hr);
        LocalDate from = nextMonday(14);
        LeaveRequestDto stale = apply(emp, "CL", from, from);
        LeaveRequestDto fresh = apply(emp, "CL", from.plusDays(7), from.plusDays(7));
        LeaveRequestDto alreadyHr = apply(register("MANAGER", null), "CL", from, from);

        LeaveRequest r = requestRepo.findById(stale.id()).orElseThrow();
        r.setAppliedOn(Instant.now().minus(10, ChronoUnit.DAYS));
        requestRepo.save(r);

        assertEquals(1, escalationJob.runOnce(Instant.now()));
        LeaveRequestDto after = requestService.toDto(requestRepo.findById(stale.id()).orElseThrow());
        assertEquals(RequestStatus.PENDING_HR, after.status());
        assertEquals(Stage.HR, after.currentStage());
        assertEquals(List.of("APPLIED", "ESCALATED"), historyActions(after));
        assertEquals("SYSTEM", after.history().get(1).actorRole());
        assertEquals("System", after.history().get(1).actorName());
        assertTrue(after.history().get(1).comment().startsWith("Auto-escalated after 3 days"));
        assertEquals(1, notificationCount(emp.id()));
        assertEquals(RequestStatus.PENDING_MANAGER, requestRepo.findById(fresh.id()).orElseThrow().getStatus());
        assertEquals(RequestStatus.PENDING_HR, requestRepo.findById(alreadyHr.id()).orElseThrow().getStatus());
        assertEquals(d("2.0"), balance(emp.id(), "CL", from.getYear()).pending());

        assertEquals(0, escalationJob.runOnce(Instant.now()), "second run is a no-op");
        assertFalse(approvalService.escalate(stale.id()));
        assertEquals(2, requestService.toDto(requestRepo.findById(stale.id()).orElseThrow()).history().size());
        // the manager can no longer act, HR now owns the request
        assertApi(HttpStatus.CONFLICT, "INVALID_TRANSITION", () -> approvalService.managerAction(mgr, stale.id(), APPROVE));
        assertEquals(RequestStatus.APPROVED, approvalService.hrAction(hr, stale.id(), APPROVE).status());
    }

    @Test
    void applyRejectsInvalidRanges() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);

        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", () -> apply(emp, "CL", from.plusDays(2), from));

        apply(emp, "CL", from, from.plusDays(1));
        assertApi(HttpStatus.CONFLICT, "LEAVE_OVERLAP", () -> apply(emp, "CL", from.plusDays(1), from.plusDays(3)));
        assertApi(HttpStatus.CONFLICT, "LEAVE_OVERLAP", () -> apply(emp, "SL", from, from));
        apply(emp, "CL", from.plusDays(2), from.plusDays(2));

        // 15 working days against a CL balance of 12 (a fresh employee, so no overlap)
        AuthUser other = register("EMPLOYEE", null);
        assertApi(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                () -> apply(other, "CL", from, from.plusDays(18)));
        assertEquals(d("12.0"), balanceService.available(other.id(), typeId("CL"), from.getYear()));

        LocalDate saturday = from.plusDays(5);
        assertEquals(DayOfWeek.SATURDAY, saturday.getDayOfWeek());
        assertApi(HttpStatus.BAD_REQUEST, "NO_WORKING_DAYS", () -> apply(other, "CL", saturday, saturday.plusDays(1)));
    }

    @Test
    void unlimitedUnpaidLeaveNeverRunsOutOfBalance() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);
        LeaveRequestDto lop = apply(emp, "LOP", from, from.plusDays(18));
        assertEquals(d("15.0"), lop.workingDays());
        assertEquals(d("15.0"), balance(emp.id(), "LOP", from.getYear()).pending());
    }

    @Test
    void attachmentRuleForSickLeaveAndAttachmentIsStored() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);

        assertApi(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED", () -> apply(emp, "SL", from, from.plusDays(3)));
        // two days or fewer need no document
        LeaveRequestDto shortSick = apply(emp, "SL", from, from.plusDays(1));
        assertNull(shortSick.attachmentUrl());

        MockMultipartFile file = new MockMultipartFile("attachment", "note.pdf", "application/pdf",
                "%PDF-1.4 medical certificate".getBytes());
        LeaveRequestDto longSick = requestService.apply(emp, request("SL", from.plusDays(7), from.plusDays(10)), file);
        assertEquals("/api/v1/leave-requests/" + longSick.id() + "/attachment", longSick.attachmentUrl());
        assertEquals(d("4.0"), longSick.workingDays());
    }

    @Test
    void rejectedApplicationStoresNoFile() throws IOException {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);
        apply(emp, "SL", from, from);
        MockMultipartFile file = new MockMultipartFile("attachment", "note.pdf", "application/pdf",
                "%PDF-1.4 medical certificate".getBytes());
        long before = storedFiles();
        assertApi(HttpStatus.CONFLICT, "LEAVE_OVERLAP", () -> requestService.apply(emp, request("SL", from, from), file));
        assertEquals(before, storedFiles());
        assertEquals(1, requestRepo.findOverlapping(emp.id(), List.of(RequestStatus.PENDING_HR), from, from).size());
    }

    private long storedFiles() throws IOException {
        Path dir = Path.of(properties.uploadDir());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void backdatingIsRejectedExceptForSickLeave() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate past = pastMonday(30);

        assertApi(HttpStatus.BAD_REQUEST, "BACKDATE_NOT_ALLOWED", () -> apply(emp, "CL", past, past));
        LeaveRequestDto sick = apply(emp, "SL", past, past.plusDays(1));
        assertEquals(RequestStatus.PENDING_HR, sick.status());

        // within the allowed window (7 days) other types are fine too
        LocalDate recentWeekday = today().minusDays(1);
        while (recentWeekday.getDayOfWeek() == DayOfWeek.SATURDAY || recentWeekday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            recentWeekday = recentWeekday.minusDays(1);
        }
        LocalDate ok = recentWeekday;
        assertEquals(RequestStatus.PENDING_HR, apply(emp, "CL", ok, ok).status());
    }

    @Test
    void minimumNoticeIsEnforcedPerLeaveType() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LeaveType type = new LeaveType();
        type.setName("Planned leave");
        type.setCode("PLN" + UUID.randomUUID().toString().substring(0, 6));
        type.setYearlyQuota(new BigDecimal("20"));
        type.setMinNoticeDays(30);
        type = typeRepo.save(type);
        Long typeId = type.getId();

        try {
            LocalDate soon = nextMonday(5);
            assertApi(HttpStatus.BAD_REQUEST, "MIN_NOTICE_NOT_MET", () -> requestService.apply(emp,
                    new ApplyRequest(typeId, soon, soon, false, null, "x", null), null));
            LocalDate later = nextMonday(35);
            assertEquals(RequestStatus.PENDING_HR, requestService
                    .apply(emp, new ApplyRequest(typeId, later, later, false, null, "x", null), null).status());
        } finally {
            // keep the shared schema's set of active leave types stable for other tests
            type.setActive(false);
            typeRepo.save(type);
        }
    }

    @Test
    void halfDayRules() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);

        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", () -> requestService.apply(emp,
                new ApplyRequest(typeId("CL"), from, from.plusDays(1), true, HalfDaySession.FIRST_HALF, "x", null), null));
        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", () -> requestService.apply(emp,
                new ApplyRequest(typeId("CL"), from, from, true, null, "x", null), null));
        // maternity leave does not allow half days
        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", () -> applyHalfDay(emp, "ML", from, HalfDaySession.FIRST_HALF));

        LeaveRequestDto half = applyHalfDay(emp, "CL", from, HalfDaySession.FIRST_HALF);
        assertEquals(d("0.5"), half.workingDays());
        assertTrue(half.halfDay());
        assertEquals(HalfDaySession.FIRST_HALF, half.halfDaySession());
        assertEquals(d("0.5"), balance(emp.id(), "CL", from.getYear()).pending());

        // the other half of the same day is still free, the same half is not
        assertEquals(d("0.5"), applyHalfDay(emp, "CL", from, HalfDaySession.SECOND_HALF).workingDays());
        assertApi(HttpStatus.CONFLICT, "LEAVE_OVERLAP", () -> applyHalfDay(emp, "CL", from, HalfDaySession.FIRST_HALF));
    }

    @Test
    void inactiveEmployeeCannotApplyAndUnknownTypeIs400() {
        AuthUser gone = user(newEmployeeId(), "EMPLOYEE");
        when(directory.get(gone.id())).thenReturn(inactive(gone));
        LocalDate from = nextMonday(14);
        assertApi(HttpStatus.FORBIDDEN, "FORBIDDEN", () -> apply(gone, "CL", from, from));

        AuthUser emp = register("EMPLOYEE", null);
        assertApi(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", () -> requestService.apply(emp,
                new ApplyRequest(999_999L, from, from, false, null, "x", null), null));
    }

    @Test
    void previewReportsProblemsAsWarningsInsteadOfFailing() {
        AuthUser emp = register("EMPLOYEE", null);
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);

        PreviewResponse ok = requestService.preview(emp, new PreviewRequest(typeId("CL"), from, from.plusDays(7), false, null));
        assertTrue(ok.isValid());
        assertTrue(ok.warnings().isEmpty());
        assertEquals(d("6.0"), ok.workingDays());
        assertEquals(List.of(from.plusDays(5), from.plusDays(6)), ok.excludedDates());
        assertEquals(d("12.0"), ok.availableBalance());

        PreviewResponse reversed = requestService.preview(emp, new PreviewRequest(typeId("CL"), from.plusDays(3), from, false, null));
        assertFalse(reversed.isValid());
        assertEquals(1, reversed.warnings().size());

        PreviewResponse tooLong = requestService.preview(emp, new PreviewRequest(typeId("CL"), from, from.plusDays(25), false, null));
        assertFalse(tooLong.isValid());
        assertTrue(tooLong.warnings().get(0).contains("Insufficient"));

        PreviewResponse weekend = requestService.preview(emp, new PreviewRequest(typeId("CL"), from.plusDays(5), from.plusDays(6), false, null));
        assertFalse(weekend.isValid());
        assertEquals(d("0.0"), weekend.workingDays());

        PreviewResponse sick = requestService.preview(emp, new PreviewRequest(typeId("SL"), from, from.plusDays(3), false, null));
        assertFalse(sick.isValid());
        assertTrue(sick.warnings().contains("Attachment is required for this leave"));

        PreviewResponse badHalf = requestService.preview(emp, new PreviewRequest(typeId("CL"), from, from.plusDays(1), true, null));
        assertFalse(badHalf.isValid());
        assertTrue(badHalf.warnings().size() >= 2);

        apply(emp, "CL", from, from);
        PreviewResponse overlap = requestService.preview(emp, new PreviewRequest(typeId("CL"), from, from, false, null));
        assertFalse(overlap.isValid());
        assertTrue(overlap.warnings().get(0).contains("overlap"));

        PreviewResponse past = requestService.preview(emp, new PreviewRequest(typeId("CL"), pastMonday(30), pastMonday(30), false, null));
        assertFalse(past.isValid());
        assertTrue(past.warnings().get(0).contains("past"));
    }

    @Test
    void toDtosBatchLoadsHistoryForAllRequests() {
        AuthUser mgr = register("MANAGER", null);
        AuthUser emp = register("EMPLOYEE", mgr.id());
        registerHrUsers(register("HR", null));
        LocalDate from = nextMonday(14);
        LeaveRequestDto a = apply(emp, "CL", from, from);
        LeaveRequestDto b = apply(emp, "CL", from.plusDays(7), from.plusDays(7));
        approvalService.managerAction(mgr, b.id(), APPROVE);

        List<LeaveRequestDto> dtos = requestService.toDtos(List.of(requestRepo.findById(a.id()).orElseThrow(),
                requestRepo.findById(b.id()).orElseThrow()));
        assertEquals(List.of("APPLIED"), historyActions(dtos.get(0)));
        assertEquals(List.of("APPLIED", "MANAGER_APPROVED"), historyActions(dtos.get(1)));
        assertEquals("Casual Leave", dtos.get(1).leaveType().name());
        assertTrue(requestService.toDtos(List.of()).isEmpty());
    }
}
