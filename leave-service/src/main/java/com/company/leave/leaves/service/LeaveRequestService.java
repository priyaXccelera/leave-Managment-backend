package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.company.leave.leaves.client.EmployeeDirectoryClient.EmployeeInfo;
import com.company.leave.leaves.config.LeaveProperties;
import com.company.leave.leaves.domain.ApprovalHistory;
import com.company.leave.leaves.domain.Enums.HalfDaySession;
import com.company.leave.leaves.domain.Enums.HistoryAction;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.Enums.Stage;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.LeaveRequestDtos.ApplyRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.CancelRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.LeaveRequestDtos.PreviewRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.PreviewResponse;
import com.company.leave.leaves.mapper.LeaveRequestMapper;
import com.company.leave.leaves.repo.ApprovalHistoryRepository;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applying for, previewing and cancelling leave, plus the entity to DTO conversion of requests.
 */
@Service
public class LeaveRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(LeaveRequestService.class);
    private static final String SICK_CODE = "SL";
    private static final Set<RequestStatus> BLOCKING = EnumSet.of(RequestStatus.PENDING_MANAGER,
            RequestStatus.PENDING_HR, RequestStatus.APPROVED);

    /** A business-rule violation found while evaluating an application. */
    private record Problem(HttpStatus status, String code, String message) {
        ApiException toException() {
            return new ApiException(status, code, message);
        }
    }

    private record Evaluation(LeaveType type, BigDecimal workingDays, List<LocalDate> excludedDates,
                              BigDecimal available, List<Problem> problems) {
    }

    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository leaveTypes;
    private final ApprovalHistoryRepository historyRepo;
    private final BalanceService balances;
    private final NotificationService notifications;
    private final WorkingDayCalculator calculator;
    private final EmployeeDirectoryClient directory;
    private final AttachmentStorage storage;
    private final LeaveProperties properties;
    private final WorkflowSupport support;

    public LeaveRequestService(LeaveRequestRepository requests, LeaveTypeRepository leaveTypes,
                               ApprovalHistoryRepository historyRepo, BalanceService balances,
                               NotificationService notifications, WorkingDayCalculator calculator,
                               EmployeeDirectoryClient directory, AttachmentStorage storage,
                               LeaveProperties properties, WorkflowSupport support) {
        this.requests = requests;
        this.leaveTypes = leaveTypes;
        this.historyRepo = historyRepo;
        this.balances = balances;
        this.notifications = notifications;
        this.calculator = calculator;
        this.directory = directory;
        this.storage = storage;
        this.properties = properties;
        this.support = support;
    }

    /** Evaluates an application without storing it; business-rule problems become warnings. */
    @Transactional(readOnly = true)
    public PreviewResponse preview(AuthUser user, PreviewRequest request) {
        Evaluation e = evaluate(user.id(), request.leaveTypeId(), request.fromDate(), request.toDate(),
                request.halfDay(), request.halfDaySession(), false);
        List<String> warnings = e.problems().stream().map(Problem::message).toList();
        return new PreviewResponse(e.workingDays(), e.excludedDates(), e.available(), warnings.isEmpty(),
                warnings);
    }

    /**
     * Creates the request, reserves the balance and notifies the first approver.
     *
     * @param attachment optional supporting document (mandatory for some types)
     */
    @Transactional
    public LeaveRequestDto apply(AuthUser user, ApplyRequest request, MultipartFile attachment) {
        EmployeeInfo employee = directory.get(user.id());
        if (!employee.active()) {
            throw ApiException.forbidden("Your account is inactive");
        }
        // serialises concurrent applications of one employee so the overlap check cannot be raced
        requests.lockEmployee(user.id());

        // an empty, unnamed part is what browsers send when no file was chosen: treat it as "no attachment"
        boolean hasAttachment = attachment != null
                && !(attachment.isEmpty() && (attachment.getOriginalFilename() == null
                || attachment.getOriginalFilename().isBlank()));
        Evaluation e = evaluate(user.id(), request.leaveTypeId(), request.fromDate(), request.toDate(),
                request.halfDay(), request.halfDaySession(), hasAttachment);
        if (!e.problems().isEmpty()) {
            throw e.problems().get(0).toException();
        }

        String storedPath = null;
        if (hasAttachment) {
            storedPath = storage.store(attachment);
            removeFileUnlessCommitted(storedPath);
        }
        try {
            return create(user, employee, request, e, storedPath);
        } catch (RuntimeException ex) {
            if (storedPath != null) {
                storage.delete(storedPath);
            }
            throw ex;
        }
    }

    /** Owner-only cancellation of a pending request, or of an approved one that has not started yet. */
    @Transactional
    public LeaveRequestDto cancel(AuthUser user, Long id, CancelRequest cancel) {
        LeaveRequest r = requests.lockById(id).orElseThrow(() -> ApiException.notFound("Leave request not found"));
        if (!r.getEmployeeId().equals(user.id())) {
            throw ApiException.forbidden("You can only cancel your own leave requests");
        }
        int year = r.getFromDate().getYear();
        RequestStatus before = r.getStatus();
        List<Long> toNotify = new ArrayList<>();
        switch (before) {
            case PENDING_MANAGER -> {
                balances.release(r.getEmployeeId(), r.getLeaveTypeId(), year, r.getWorkingDays());
                if (r.getManagerIdSnapshot() != null) {
                    toNotify.add(r.getManagerIdSnapshot());
                }
            }
            case PENDING_HR -> {
                balances.release(r.getEmployeeId(), r.getLeaveTypeId(), year, r.getWorkingDays());
                toNotify.addAll(support.hrIds(user.id()));
            }
            case APPROVED -> {
                if (!today().isBefore(r.getFromDate())) {
                    throw ApiException.conflict("INVALID_TRANSITION",
                            "An approved leave that has already started cannot be cancelled");
                }
                balances.restore(r.getEmployeeId(), r.getLeaveTypeId(), year, r.getWorkingDays());
                if (r.getManagerIdSnapshot() != null) {
                    toNotify.add(r.getManagerIdSnapshot());
                }
                toNotify.addAll(support.hrIds(user.id()));
            }
            default -> throw ApiException.conflict("INVALID_TRANSITION",
                    "A request with status " + before + " cannot be cancelled");
        }
        r.setStatus(RequestStatus.CANCELLED);
        r.setCurrentStage(Stage.NONE);
        r.setFinalDecisionDate(Instant.now());
        requests.save(r);
        support.record(r, HistoryAction.CANCELLED, user.id(), user.name(), user.role(),
                cancel == null ? null : cancel.comment());
        notifications.notifyAll(toNotify, r.getEmployeeName() + " cancelled the leave request for "
                + r.getFromDate() + " to " + r.getToDate(), r.getId());
        return toDto(r);
    }

    @Transactional(readOnly = true)
    public LeaveRequestDto toDto(LeaveRequest request) {
        LeaveType type = leaveTypes.findById(request.getLeaveTypeId()).orElse(null);
        List<ApprovalHistory> history = historyRepo.findByRequestIdOrderByTimestampAsc(request.getId());
        return LeaveRequestMapper.toDto(request, type, history);
    }

    /** Batch conversion: one query for the leave types and one for all histories. */
    @Transactional(readOnly = true)
    public List<LeaveRequestDto> toDtos(List<LeaveRequest> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> typeIds = list.stream().map(LeaveRequest::getLeaveTypeId).collect(Collectors.toSet());
        Map<Long, LeaveType> types = leaveTypes.findAllById(typeIds).stream()
                .collect(Collectors.toMap(LeaveType::getId, t -> t));
        List<Long> ids = list.stream().map(LeaveRequest::getId).toList();
        Map<Long, List<ApprovalHistory>> history = new HashMap<>();
        for (ApprovalHistory h : historyRepo.findByRequestIdInOrderByTimestampAsc(ids)) {
            history.computeIfAbsent(h.getRequestId(), k -> new ArrayList<>()).add(h);
        }
        return list.stream()
                .map(r -> LeaveRequestMapper.toDto(r, types.get(r.getLeaveTypeId()),
                        history.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    private LeaveRequestDto create(AuthUser user, EmployeeInfo employee, ApplyRequest request, Evaluation e,
                                   String storedPath) {
        LeaveType type = e.type();
        int year = request.fromDate().getYear();
        balances.reserve(user.id(), type.getId(), year, e.workingDays());

        Long managerId = employee.managerId();
        boolean toManager = "EMPLOYEE".equals(user.role()) && managerId != null;

        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(user.id());
        r.setEmployeeCode(user.employeeCode());
        r.setEmployeeName(user.name());
        r.setDepartment(user.department());
        r.setManagerIdSnapshot(managerId);
        r.setLeaveTypeId(type.getId());
        r.setFromDate(request.fromDate());
        r.setToDate(request.toDate());
        r.setHalfDay(request.halfDay());
        r.setHalfDaySession(request.halfDay() ? request.halfDaySession() : null);
        r.setWorkingDays(e.workingDays());
        r.setReason(request.reason().trim());
        r.setContactNumber(request.contactNumber() == null || request.contactNumber().isBlank() ? null
                : request.contactNumber().trim());
        r.setAttachmentPath(storedPath);
        r.setStatus(toManager ? RequestStatus.PENDING_MANAGER : RequestStatus.PENDING_HR);
        r.setCurrentStage(toManager ? Stage.MANAGER : Stage.HR);
        r.setAppliedOn(Instant.now());
        r = requests.save(r);

        support.record(r, HistoryAction.APPLIED, user.id(), user.name(), user.role(), null);
        String message = user.name() + " applied for " + type.getName() + " from " + r.getFromDate() + " to "
                + r.getToDate() + " (" + e.workingDays() + " day(s))";
        if (toManager) {
            notifications.notify(managerId, message, r.getId());
        } else {
            notifications.notifyAll(support.hrIds(user.id()), message, r.getId());
        }
        LOG.info("Leave request {} created by employee {} ({} {} - {})", r.getId(), user.id(), type.getCode(),
                r.getFromDate(), r.getToDate());
        return toDto(r);
    }

    /** Applies all rules and collects every violation (the first one is what {@code apply} reports). */
    private Evaluation evaluate(Long employeeId, Long leaveTypeId, LocalDate from, LocalDate to, boolean halfDay,
                                HalfDaySession session, boolean hasAttachment) {
        LeaveType type = leaveTypes.findById(leaveTypeId).orElseThrow(() -> ApiException.validation(
                "Unknown leave type", List.of(new FieldError("leaveTypeId", "unknown leave type"))));
        List<Problem> problems = new ArrayList<>();
        if (!type.isActive()) {
            problems.add(validation("Leave type " + type.getName() + " is not active"));
        }
        int year = from.getYear();
        BigDecimal available = balances.available(employeeId, type.getId(), year);
        if (from.isAfter(to)) {
            problems.add(validation("From date must not be after to date"));
            return new Evaluation(type, BigDecimal.ZERO.setScale(1), List.of(), available, problems);
        }

        if (halfDay) {
            if (!type.isHalfDayAllowed()) {
                problems.add(validation(type.getName() + " does not allow half-day leave"));
            }
            if (!from.equals(to)) {
                problems.add(validation("Half-day leave is only allowed for a single day"));
            }
            if (session == null) {
                problems.add(validation("Half-day session (FIRST_HALF or SECOND_HALF) is required"));
            }
        }
        WorkingDayCalculator.Result calc = calculator.calculate(from, to, halfDay);
        BigDecimal days = calc.workingDays();
        if (days.signum() == 0) {
            problems.add(new Problem(HttpStatus.BAD_REQUEST, "NO_WORKING_DAYS",
                    "The selected dates contain no working days"));
        }

        LocalDate today = today();
        if (!SICK_CODE.equalsIgnoreCase(type.getCode()) && from.isBefore(today.minusDays(properties.backdateDays()))) {
            problems.add(new Problem(HttpStatus.BAD_REQUEST, "BACKDATE_NOT_ALLOWED",
                    "Leave cannot be applied for dates more than " + properties.backdateDays() + " days in the past"));
        }
        if (type.getMinNoticeDays() > 0 && from.isBefore(today.plusDays(type.getMinNoticeDays()))) {
            problems.add(new Problem(HttpStatus.BAD_REQUEST, "MIN_NOTICE_NOT_MET", type.getName()
                    + " requires at least " + type.getMinNoticeDays() + " day(s) notice"));
        }
        if (hasOverlap(employeeId, from, to, halfDay, session)) {
            problems.add(new Problem(HttpStatus.CONFLICT, "LEAVE_OVERLAP",
                    "The dates overlap with an existing pending or approved leave request"));
        }
        if (!type.isUnlimited() && type.isPaid() && days.compareTo(available) > 0) {
            problems.add(new Problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE",
                    "Insufficient " + type.getName() + " balance: " + available + " day(s) available, " + days
                            + " requested"));
        }
        if (!hasAttachment && attachmentRequired(type, days)) {
            problems.add(new Problem(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED",
                    "Attachment is required for this leave"));
        }
        return new Evaluation(type, days, calc.excludedDates(), available, problems);
    }

    private boolean hasOverlap(Long employeeId, LocalDate from, LocalDate to, boolean halfDay,
                               HalfDaySession session) {
        return requests.findOverlapping(employeeId, BLOCKING, from, to).stream().anyMatch(existing -> {
            boolean complementaryHalves = halfDay && existing.isHalfDay() && from.equals(to)
                    && existing.getFromDate().equals(existing.getToDate())
                    && session != null && existing.getHalfDaySession() != null
                    && session != existing.getHalfDaySession();
            return !complementaryHalves;
        });
    }

    private static boolean attachmentRequired(LeaveType type, BigDecimal days) {
        if (!type.isAttachmentRequired()) {
            return false;
        }
        Integer after = type.getAttachmentRequiredAfterDays();
        return after == null || days.compareTo(BigDecimal.valueOf(after)) > 0;
    }

    private static Problem validation(String message) {
        return new Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** Deletes the stored file if the surrounding transaction does not commit. */
    private void removeFileUnlessCommitted(String path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.delete(path);
                }
            }
        });
    }
}
