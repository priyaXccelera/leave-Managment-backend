package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.company.leave.leaves.config.LeaveProperties;
import com.company.leave.leaves.domain.Enums.HistoryAction;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.Enums.Stage;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.ActionRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Two-stage approval workflow: manager first, then HR (final). Every transition locks the request row,
 * checks the current status and moves the balance (pending to used / back to available).
 */
@Service
public class ApprovalService {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalService.class);
    static final String SYSTEM_ROLE = "SYSTEM";

    private enum Decision { APPROVE, REJECT }

    private final LeaveRequestRepository requests;
    private final BalanceService balances;
    private final NotificationService notifications;
    private final EmployeeDirectoryClient directory;
    private final LeaveRequestService requestService;
    private final LeaveProperties properties;
    private final WorkflowSupport support;

    public ApprovalService(LeaveRequestRepository requests, BalanceService balances,
                           NotificationService notifications, EmployeeDirectoryClient directory,
                           LeaveRequestService requestService, LeaveProperties properties,
                           WorkflowSupport support) {
        this.requests = requests;
        this.balances = balances;
        this.notifications = notifications;
        this.directory = directory;
        this.requestService = requestService;
        this.properties = properties;
        this.support = support;
    }

    /** Manager decision on a direct report's request (PENDING_MANAGER only). */
    @Transactional
    public LeaveRequestDto managerAction(AuthUser manager, Long id, ActionRequest action) {
        if (!"MANAGER".equals(manager.role())) {
            throw ApiException.forbidden("Only managers can act at the manager stage");
        }
        Decision decision = parse(action);
        String comment = comment(action, decision);
        LeaveRequest r = lock(id);
        if (!manager.id().equals(r.getManagerIdSnapshot())
                || !Objects.equals(directory.get(r.getEmployeeId()).managerId(), manager.id())) {
            throw ApiException.forbidden("You can only act on requests of your direct reports");
        }
        requireStatus(r, RequestStatus.PENDING_MANAGER);

        if (decision == Decision.APPROVE) {
            r.setStatus(RequestStatus.PENDING_HR);
            r.setCurrentStage(Stage.HR);
            requests.save(r);
            support.record(r, HistoryAction.MANAGER_APPROVED, manager.id(), manager.name(), manager.role(),
                    comment);
            notifications.notifyAll(support.hrIds(r.getEmployeeId()), describe(r) + " was approved by "
                    + manager.name() + " and awaits HR approval", r.getId());
        } else {
            reject(r, RequestStatus.REJECTED_BY_MANAGER);
            support.record(r, HistoryAction.MANAGER_REJECTED, manager.id(), manager.name(), manager.role(),
                    comment);
            notifications.notify(r.getEmployeeId(), "Your leave request for " + r.getFromDate() + " to "
                    + r.getToDate() + " was rejected by your manager", r.getId());
        }
        return requestService.toDto(r);
    }

    /** HR decision (PENDING_HR only); approval is final and deducts the balance. */
    @Transactional
    public LeaveRequestDto hrAction(AuthUser hr, Long id, ActionRequest action) {
        if (!hr.isHr()) {
            throw ApiException.forbidden("Only HR can act at the HR stage");
        }
        Decision decision = parse(action);
        String comment = comment(action, decision);
        LeaveRequest r = lock(id);
        if (r.getEmployeeId().equals(hr.id())) {
            throw ApiException.forbidden("HR cannot act on their own leave request");
        }
        requireStatus(r, RequestStatus.PENDING_HR);

        List<Long> recipients = new ArrayList<>();
        recipients.add(r.getEmployeeId());
        if (r.getManagerIdSnapshot() != null) {
            recipients.add(r.getManagerIdSnapshot());
        }
        String period = r.getFromDate() + " to " + r.getToDate();
        if (decision == Decision.APPROVE) {
            r.setStatus(RequestStatus.APPROVED);
            r.setCurrentStage(Stage.NONE);
            r.setFinalDecisionDate(Instant.now());
            requests.save(r);
            balances.deduct(r.getEmployeeId(), r.getLeaveTypeId(), r.getFromDate().getYear(), r.getWorkingDays());
            support.record(r, HistoryAction.HR_APPROVED, hr.id(), hr.name(), hr.role(), comment);
            notifications.notifyAll(recipients, describe(r) + " (" + period + ") was approved by HR", r.getId());
        } else {
            reject(r, RequestStatus.REJECTED_BY_HR);
            support.record(r, HistoryAction.HR_REJECTED, hr.id(), hr.name(), hr.role(), comment);
            notifications.notifyAll(recipients, describe(r) + " (" + period + ") was rejected by HR", r.getId());
        }
        return requestService.toDto(r);
    }

    /**
     * Moves a request that waited too long at the manager stage to HR. Idempotent.
     *
     * @return true if the request was escalated, false if it was no longer PENDING_MANAGER
     */
    @Transactional
    public boolean escalate(Long requestId) {
        LeaveRequest r = requests.lockById(requestId).orElse(null);
        if (r == null || r.getStatus() != RequestStatus.PENDING_MANAGER) {
            return false;
        }
        r.setStatus(RequestStatus.PENDING_HR);
        r.setCurrentStage(Stage.HR);
        requests.save(r);
        String comment = "Auto-escalated after " + properties.escalationDays() + " days without manager action";
        support.record(r, HistoryAction.ESCALATED, 0L, "System", SYSTEM_ROLE, comment);
        List<Long> recipients = new ArrayList<>(support.hrIds(r.getEmployeeId()));
        notifications.notifyAll(recipients, describe(r) + " was escalated to HR (no manager action)", r.getId());
        notifications.notify(r.getEmployeeId(), "Your leave request for " + r.getFromDate() + " to "
                + r.getToDate() + " was escalated to HR", r.getId());
        LOG.info("Leave request {} escalated to HR", r.getId());
        return true;
    }

    private void reject(LeaveRequest r, RequestStatus status) {
        balances.release(r.getEmployeeId(), r.getLeaveTypeId(), r.getFromDate().getYear(), r.getWorkingDays());
        r.setStatus(status);
        r.setCurrentStage(Stage.NONE);
        r.setFinalDecisionDate(Instant.now());
        requests.save(r);
    }

    private LeaveRequest lock(Long id) {
        return requests.lockById(id).orElseThrow(() -> ApiException.notFound("Leave request not found"));
    }

    private static void requireStatus(LeaveRequest r, RequestStatus expected) {
        if (r.getStatus() != expected) {
            throw ApiException.conflict("INVALID_TRANSITION", "Request is " + r.getStatus()
                    + "; this action requires status " + expected);
        }
    }

    private static Decision parse(ActionRequest action) {
        String value = action == null || action.action() == null ? "" : action.action().trim()
                .toUpperCase(Locale.ROOT);
        try {
            return Decision.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("Action must be APPROVE or REJECT",
                    List.of(new FieldError("action", "must be APPROVE or REJECT")));
        }
    }

    private static String comment(ActionRequest action, Decision decision) {
        String c = action.comment() == null ? null : action.comment().trim();
        if (decision == Decision.REJECT && (c == null || c.isEmpty())) {
            throw ApiException.validation("A comment is required when rejecting",
                    List.of(new FieldError("comment", "must not be blank when rejecting")));
        }
        return c;
    }

    private static String describe(LeaveRequest r) {
        return "Leave request of " + r.getEmployeeName();
    }
}
