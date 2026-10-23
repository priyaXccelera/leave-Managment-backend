package com.company.leave.leaves;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.client.EmployeeDirectoryClient.EmployeeInfo;
import com.company.leave.leaves.domain.Enums.HalfDaySession;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.Enums.Stage;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.ApplyRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.MasterDtos.BalanceDto;
import com.company.leave.leaves.repo.ApprovalHistoryRepository;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import com.company.leave.leaves.repo.NotificationRepository;
import com.company.leave.leaves.service.ApprovalService;
import com.company.leave.leaves.service.BalanceService;
import com.company.leave.leaves.service.EscalationJob;
import com.company.leave.leaves.service.LeaveRequestService;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Shared fixtures for the workflow and permission tests: services, repositories, directory stubbing and
 * weekday-safe date helpers (so tests never depend on the day they run).
 */
public abstract class WorkflowTestSupport extends AbstractIntegrationTest {

    @Autowired
    protected LeaveRequestService requestService;
    @Autowired
    protected ApprovalService approvalService;
    @Autowired
    protected EscalationJob escalationJob;
    @Autowired
    protected BalanceService balanceService;
    @Autowired
    protected LeaveRequestRepository requestRepo;
    @Autowired
    protected LeaveTypeRepository typeRepo;
    @Autowired
    protected ApprovalHistoryRepository historyRepo;
    @Autowired
    protected NotificationRepository notificationRepo;

    protected static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** First Monday at least {@code minDaysAhead} days from today. */
    protected static LocalDate nextMonday(int minDaysAhead) {
        LocalDate d = today().plusDays(minDaysAhead);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Last Monday at least {@code minDaysBack} days ago. */
    protected static LocalDate pastMonday(int minDaysBack) {
        LocalDate d = today().minusDays(minDaysBack);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** Registers the user in the mocked employee directory and returns it. */
    protected AuthUser register(String role, Long managerId) {
        AuthUser u = user(newEmployeeId(), role);
        when(directory.get(u.id())).thenReturn(info(u, managerId));
        return u;
    }

    protected void registerHrUsers(AuthUser... hrs) {
        when(directory.hrUsers()).thenReturn(java.util.Arrays.stream(hrs).map(h -> info(h, null)).toList());
    }

    protected void registerReports(AuthUser manager, AuthUser... reports) {
        when(directory.directReports(manager.id())).thenReturn(
                java.util.Arrays.stream(reports).map(r -> info(r, manager.id())).toList());
    }

    protected Long typeId(String code) {
        return typeRepo.findByCodeIgnoreCase(code).orElseThrow().getId();
    }

    protected ApplyRequest request(String typeCode, LocalDate from, LocalDate to) {
        return new ApplyRequest(typeId(typeCode), from, to, false, null, "Personal work", null);
    }

    protected LeaveRequestDto apply(AuthUser u, String typeCode, LocalDate from, LocalDate to) {
        return requestService.apply(u, request(typeCode, from, to), null);
    }

    protected LeaveRequestDto applyHalfDay(AuthUser u, String typeCode, LocalDate day, HalfDaySession session) {
        return requestService.apply(u, new ApplyRequest(typeId(typeCode), day, day, true, session, "Errand", null),
                null);
    }

    protected BalanceDto balance(Long employeeId, String code, int year) {
        return balanceService.forEmployee(employeeId, year).stream()
                .filter(b -> code.equals(b.code())).findFirst().orElseThrow();
    }

    protected long notificationCount(Long recipientId) {
        return notificationRepo.findByRecipientId(recipientId, PageRequest.of(0, 1)).getTotalElements();
    }

    /** Stores an arbitrary request directly (bypassing the rules) for state set-ups. */
    protected LeaveRequest insertRequest(AuthUser u, Long managerId, String typeCode, LocalDate from, LocalDate to,
                                         RequestStatus status) {
        LeaveRequest r = new LeaveRequest();
        r.setEmployeeId(u.id());
        r.setEmployeeCode(u.employeeCode());
        r.setEmployeeName(u.name());
        r.setDepartment(u.department());
        r.setManagerIdSnapshot(managerId);
        r.setLeaveTypeId(typeId(typeCode));
        r.setFromDate(from);
        r.setToDate(to);
        r.setWorkingDays(BigDecimal.ONE.setScale(1));
        r.setReason("seeded");
        r.setStatus(status);
        r.setCurrentStage(status == RequestStatus.PENDING_MANAGER ? Stage.MANAGER
                : status == RequestStatus.PENDING_HR ? Stage.HR : Stage.NONE);
        r.setAppliedOn(Instant.now());
        return requestRepo.save(r);
    }

    protected static ApiException assertApi(HttpStatus status, String code, Executable executable) {
        ApiException ex = assertThrows(ApiException.class, executable);
        assertEquals(status, ex.getStatus(), ex.getMessage());
        assertEquals(code, ex.getCode(), ex.getMessage());
        return ex;
    }

    protected static EmployeeInfo inactive(AuthUser u) {
        return new EmployeeInfo(u.id(), u.employeeCode(), u.name(), u.email(), u.role(), u.department(), null,
                false);
    }

    protected static List<String> historyActions(LeaveRequestDto dto) {
        return dto.history().stream().map(h -> h.action().name()).toList();
    }
}
