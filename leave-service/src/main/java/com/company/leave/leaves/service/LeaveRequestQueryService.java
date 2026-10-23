package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.common.security.AuthUser;
import com.company.leave.leaves.client.EmployeeDirectoryClient;
import com.company.leave.leaves.client.EmployeeDirectoryClient.EmployeeInfo;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.MasterDtos.CalendarEntry;
import com.company.leave.leaves.mapper.LeaveRequestMapper;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import com.company.leave.leaves.web.PageRequests;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read side of leave requests: filtered/paged lists (database-level), detail, attachment and team calendar.
 */
@Service
public class LeaveRequestQueryService {

    static final Set<String> SORT_FIELDS = Set.of("id", "appliedOn", "fromDate", "toDate", "status", "workingDays",
            "employeeName", "department");
    private static final String DEFAULT_SORT = "appliedOn,desc";
    private static final long MAX_CALENDAR_DAYS = 366;
    private static final Set<RequestStatus> CALENDAR_STATUSES = EnumSet.of(RequestStatus.PENDING_MANAGER,
            RequestStatus.PENDING_HR, RequestStatus.APPROVED);

    /** A stored attachment ready to be streamed. */
    public record AttachmentFile(Resource resource, String filename, String contentType) {
    }

    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository leaveTypes;
    private final LeaveRequestService requestService;
    private final EmployeeDirectoryClient directory;
    private final AttachmentStorage storage;

    public LeaveRequestQueryService(LeaveRequestRepository requests, LeaveTypeRepository leaveTypes,
                                    LeaveRequestService requestService, EmployeeDirectoryClient directory,
                                    AttachmentStorage storage) {
        this.requests = requests;
        this.leaveTypes = leaveTypes;
        this.requestService = requestService;
        this.directory = directory;
        this.storage = storage;
    }

    /** The caller's own requests. */
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> myRequests(AuthUser user, String status, Long leaveTypeId, LocalDate from,
                                            LocalDate to, Integer page, Integer pageSize, String sort) {
        Specification<LeaveRequest> spec = Specification.where(equalTo("employeeId", user.id()))
                .and(filters(parseStatus(status), leaveTypeId, from, to));
        return query(spec, page, pageSize, sort);
    }

    /** Requests of the manager's direct reports waiting for the manager. */
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> pendingForManager(AuthUser user, Integer page, Integer pageSize, String sort) {
        requireRole(user, "MANAGER");
        Specification<LeaveRequest> spec = Specification.where(equalTo("managerIdSnapshot", user.id()))
                .and(equalTo("status", RequestStatus.PENDING_MANAGER));
        return query(spec, page, pageSize, sort);
    }

    /** All requests of the manager's direct reports, optionally filtered. */
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> team(AuthUser user, String status, LocalDate from, LocalDate to, Integer page,
                                      Integer pageSize, String sort) {
        requireRole(user, "MANAGER");
        Specification<LeaveRequest> spec = Specification.where(equalTo("managerIdSnapshot", user.id()))
                .and(filters(parseStatus(status), null, from, to));
        return query(spec, page, pageSize, sort);
    }

    /** Requests waiting for HR, except the caller's own (HR cannot act on those). */
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> pendingForHr(AuthUser user, Integer page, Integer pageSize, String sort) {
        requireRole(user, "HR");
        Specification<LeaveRequest> spec = Specification.where(equalTo("status", RequestStatus.PENDING_HR))
                .and((root, q, cb) -> cb.notEqual(root.get("employeeId"), user.id()));
        return query(spec, page, pageSize, sort);
    }

    /** HR view over every request. */
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> all(AuthUser user, String status, Long employeeId, String department,
                                     Long leaveTypeId, LocalDate from, LocalDate to, Integer page,
                                     Integer pageSize, String sort) {
        requireRole(user, "HR");
        Specification<LeaveRequest> spec = Specification.where(equalTo("employeeId", employeeId))
                .and(equalTo("department", department == null || department.isBlank() ? null : department))
                .and(filters(parseStatus(status), leaveTypeId, from, to));
        return query(spec, page, pageSize, sort);
    }

    /** Detail for the owner, the employee's manager or HR. */
    @Transactional(readOnly = true)
    public LeaveRequestDto detail(AuthUser user, Long id) {
        return requestService.toDto(findVisible(user, id));
    }

    /** The attachment of a visible request. */
    @Transactional(readOnly = true)
    public AttachmentFile attachment(AuthUser user, Long id) {
        LeaveRequest r = findVisible(user, id);
        if (r.getAttachmentPath() == null) {
            throw ApiException.notFound("This leave request has no attachment");
        }
        Resource resource = storage.load(r.getAttachmentPath());
        String contentType = AttachmentStorage.contentTypeOf(r.getAttachmentPath());
        String filename = "leave-request-" + r.getId() + "-attachment."
                + AttachmentStorage.extensionOf(r.getAttachmentPath());
        return new AttachmentFile(resource, filename, contentType);
    }

    /**
     * Approved and pending leaves overlapping the range: managers see themselves and their direct reports,
     * HR sees everybody (optionally one department).
     */
    @Transactional(readOnly = true)
    public List<CalendarEntry> calendar(AuthUser user, LocalDate from, LocalDate to, String department) {
        if (!user.isHr() && !user.isManager()) {
            throw ApiException.forbidden("The team calendar is available to managers and HR only");
        }
        requireValidRange(from, to);
        List<LeaveRequest> found;
        boolean hasDepartment = department != null && !department.isBlank();
        if (user.isHr()) {
            found = hasDepartment
                    ? requests.findInRangeForDepartment(CALENDAR_STATUSES, department, from, to)
                    : requests.findInRange(CALENDAR_STATUSES, from, to);
        } else {
            Set<Long> ids = directory.directReports(user.id()).stream().map(EmployeeInfo::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            ids.add(user.id());
            found = requests.findInRangeForEmployees(CALENDAR_STATUSES, ids, from, to);
            if (hasDepartment) {
                found = found.stream().filter(r -> department.equals(r.getDepartment())).toList();
            }
        }
        Map<Long, String> typeNames = typeNames(found);
        return found.stream()
                .map(r -> LeaveRequestMapper.toCalendar(r, typeNames.get(r.getLeaveTypeId())))
                .toList();
    }

    static void requireValidRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.validation("'from' must not be after 'to'",
                    List.of(new FieldError("from", "must not be after 'to'")));
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_CALENDAR_DAYS) {
            throw ApiException.validation("The range must not exceed " + MAX_CALENDAR_DAYS + " days",
                    List.of(new FieldError("to", "range exceeds " + MAX_CALENDAR_DAYS + " days")));
        }
    }

    private Map<Long, String> typeNames(Collection<LeaveRequest> list) {
        Set<Long> ids = list.stream().map(LeaveRequest::getLeaveTypeId).collect(Collectors.toSet());
        return leaveTypes.findAllById(ids).stream().collect(Collectors.toMap(LeaveType::getId, LeaveType::getName));
    }

    private LeaveRequest findVisible(AuthUser user, Long id) {
        LeaveRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Leave request not found"));
        boolean owner = r.getEmployeeId().equals(user.id());
        boolean manager = user.id().equals(r.getManagerIdSnapshot());
        if (!owner && !manager && !user.isHr()) {
            throw ApiException.forbidden("You are not allowed to view this leave request");
        }
        return r;
    }

    private Page<LeaveRequestDto> query(Specification<LeaveRequest> spec, Integer page, Integer pageSize,
                                        String sort) {
        Pageable pageable = PageRequests.of(page, pageSize, sort, SORT_FIELDS, DEFAULT_SORT);
        Page<LeaveRequest> result = requests.findAll(spec, pageable);
        return new PageImpl<>(requestService.toDtos(result.getContent()), pageable, result.getTotalElements());
    }

    private static Specification<LeaveRequest> filters(RequestStatus status, Long leaveTypeId, LocalDate from,
                                                       LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.validation("'from' must not be after 'to'",
                    List.of(new FieldError("from", "must not be after 'to'")));
        }
        List<Specification<LeaveRequest>> parts = new ArrayList<>();
        parts.add(equalTo("status", status));
        parts.add(equalTo("leaveTypeId", leaveTypeId));
        if (to != null) {
            parts.add((root, q, cb) -> cb.lessThanOrEqualTo(root.get("fromDate"), to));
        }
        if (from != null) {
            parts.add((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("toDate"), from));
        }
        Specification<LeaveRequest> spec = Specification.where(null);
        for (Specification<LeaveRequest> part : parts) {
            spec = spec.and(part);
        }
        return spec;
    }

    /** Equality predicate; a null value means "no filter". */
    private static Specification<LeaveRequest> equalTo(String field, Object value) {
        return value == null ? null : (root, q, cb) -> cb.equal(root.get(field), value);
    }

    private static RequestStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RequestStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("Invalid status '" + status + "'; allowed: "
                            + Arrays.toString(RequestStatus.values()),
                    List.of(new FieldError("status", "invalid status")));
        }
    }

    private static void requireRole(AuthUser user, String role) {
        if (!role.equals(user.role())) {
            throw ApiException.forbidden("You do not have permission to perform this action");
        }
    }
}
