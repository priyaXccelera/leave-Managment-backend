package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.leaves.domain.Enums.RequestStatus;
import com.company.leave.leaves.domain.LeaveRequest;
import com.company.leave.leaves.domain.LeaveType;
import com.company.leave.leaves.dto.MasterDtos.OnLeaveDto;
import com.company.leave.leaves.dto.MasterDtos.SummaryRow;
import com.company.leave.leaves.mapper.LeaveRequestMapper;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.LeaveRequestRepository;
import com.company.leave.leaves.repo.LeaveTypeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aggregated HR reports, computed with database GROUP BY queries, and the CSV export.
 */
@Service
public class ReportService {

    private static final Set<String> GROUPS = Set.of("employee", "department", "leaveType", "month");
    private static final Set<RequestStatus> PENDING = EnumSet.of(RequestStatus.PENDING_MANAGER,
            RequestStatus.PENDING_HR);
    private static final Set<RequestStatus> REJECTED = EnumSet.of(RequestStatus.REJECTED_BY_MANAGER,
            RequestStatus.REJECTED_BY_HR);
    private static final String CSV_HEADER = "id,employeeCode,employeeName,department,leaveType,fromDate,toDate,"
            + "workingDays,status,appliedOn,finalDecisionDate";
    private static final String CRLF = "\r\n";
    private static final int CSV_BATCH = 500;
    private static final String NO_DEPARTMENT = "(none)";

    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository leaveTypes;

    @PersistenceContext
    private EntityManager em;

    public ReportService(LeaveRequestRepository requests, LeaveTypeRepository leaveTypes) {
        this.requests = requests;
        this.leaveTypes = leaveTypes;
    }

    /**
     * Requests grouped by employee, department, leaveType or month (YYYY-MM of the start date).
     * The period is the {@code year}, narrowed/overridden by {@code from}/{@code to} (inclusive, on the
     * start date). {@code approved}/{@code pending}/{@code rejected} count requests (PENDING_* and
     * REJECTED_* respectively); cancelled requests only appear in {@code requests} and {@code days}.
     */
    @Transactional(readOnly = true)
    public List<SummaryRow> summary(String groupBy, Integer year, LocalDate from, LocalDate to) {
        if (groupBy == null || !GROUPS.contains(groupBy)) {
            throw ApiException.validation("groupBy must be one of employee, department, leaveType, month",
                    List.of(new FieldError("groupBy", "must be one of employee, department, leaveType, month")));
        }
        int y = year == null ? BalanceService.currentYear() : year;
        LocalDate start = from != null ? from : LocalDate.of(y, 1, 1);
        LocalDate end = to != null ? to : LocalDate.of(y, 12, 31);
        if (start.isAfter(end)) {
            throw ApiException.validation("'from' must not be after 'to'",
                    List.of(new FieldError("from", "must not be after 'to'")));
        }
        String keys;
        String group;
        switch (groupBy) {
            case "employee" -> {
                keys = "r.employeeId, max(r.employeeName)";
                group = "r.employeeId";
            }
            case "department" -> {
                keys = "r.department, r.department";
                group = "r.department";
            }
            case "leaveType" -> {
                keys = "r.leaveTypeId, r.leaveTypeId";
                group = "r.leaveTypeId";
            }
            default -> {
                keys = "year(r.fromDate), month(r.fromDate)";
                group = "year(r.fromDate), month(r.fromDate)";
            }
        }
        String jpql = "select " + keys + ", count(r), coalesce(sum(r.workingDays), 0), "
                + "sum(case when r.status = :approved then 1 else 0 end), "
                + "sum(case when r.status in :pending then 1 else 0 end), "
                + "sum(case when r.status in :rejected then 1 else 0 end) "
                + "from LeaveRequest r where r.fromDate >= :start and r.fromDate <= :end "
                + "group by " + group + " order by " + group;
        Query query = em.createQuery(jpql);
        query.setParameter("approved", RequestStatus.APPROVED);
        query.setParameter("pending", PENDING);
        query.setParameter("rejected", REJECTED);
        query.setParameter("start", start);
        query.setParameter("end", end);

        Map<Long, String> typeNames = "leaveType".equals(groupBy)
                ? leaveTypes.findAll().stream().collect(Collectors.toMap(LeaveType::getId, LeaveType::getName))
                : Map.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> toRow(groupBy, row, typeNames)).toList();
    }

    /** Approved leaves overlapping the range (default: today), soonest first. */
    @Transactional(readOnly = true)
    public List<OnLeaveDto> onLeave(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = from == null ? (to == null ? today : to) : from;
        LocalDate end = to == null ? start : to;
        LeaveRequestQueryService.requireValidRange(start, end);
        List<LeaveRequest> found = requests.findInRange(List.of(RequestStatus.APPROVED), start, end);
        Map<Long, String> names = typeNames();
        return found.stream().map(r -> LeaveRequestMapper.toOnLeave(r, names.get(r.getLeaveTypeId()))).toList();
    }

    /**
     * RFC 4180 CSV of the leave requests whose leave starts in the year, optionally restricted to one leave
     * type and/or status. Cells starting with = + - @ are prefixed with an apostrophe (CSV injection guard).
     */
    @Transactional(readOnly = true)
    public String exportCsv(Integer year, Long leaveTypeId, String status) {
        int y = year == null ? BalanceService.currentYear() : year;
        RequestStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = RequestStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw ApiException.validation("Invalid status '" + status + "'",
                        List.of(new FieldError("status", "invalid status")));
            }
        }
        LocalDate start = LocalDate.of(y, 1, 1);
        LocalDate end = LocalDate.of(y, 12, 31);
        RequestStatus finalStatus = statusFilter;
        Specification<LeaveRequest> spec = (root, q, cb) -> cb.between(root.get("fromDate"), start, end);
        if (leaveTypeId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("leaveTypeId"), leaveTypeId));
        }
        if (finalStatus != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), finalStatus));
        }
        Map<Long, String> names = typeNames();
        StringBuilder csv = new StringBuilder(CSV_HEADER).append(CRLF);
        int page = 0;
        List<LeaveRequest> batch;
        do {
            batch = requests.findAll(spec, PageRequest.of(page++, CSV_BATCH,
                    Sort.by("fromDate", "id"))).getContent();
            for (LeaveRequest r : batch) {
                csv.append(String.join(",",
                        cell(r.getId()), cell(r.getEmployeeCode()), cell(r.getEmployeeName()),
                        cell(r.getDepartment()), cell(names.get(r.getLeaveTypeId())), cell(r.getFromDate()),
                        cell(r.getToDate()), cell(MasterMapper.days(r.getWorkingDays())), cell(r.getStatus()),
                        cell(r.getAppliedOn()), cell(r.getFinalDecisionDate()))).append(CRLF);
            }
        } while (batch.size() == CSV_BATCH);
        return csv.toString();
    }

    /** One CSV cell: formula guard first, then RFC 4180 quoting. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof Instant i ? DateTimeFormatter.ISO_INSTANT.format(i)
                : value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private Map<Long, String> typeNames() {
        return leaveTypes.findAll().stream().collect(Collectors.toMap(LeaveType::getId, LeaveType::getName));
    }

    private static SummaryRow toRow(String groupBy, Object[] row, Map<Long, String> typeNames) {
        String key;
        String label;
        switch (groupBy) {
            case "employee" -> {
                key = String.valueOf(row[0]);
                label = row[1] == null ? key : row[1].toString();
            }
            case "department" -> {
                key = row[0] == null ? NO_DEPARTMENT : row[0].toString();
                label = key;
            }
            case "leaveType" -> {
                key = String.valueOf(row[0]);
                label = typeNames.getOrDefault((Long) row[0], key);
            }
            default -> {
                key = String.format(Locale.ROOT, "%04d-%02d", ((Number) row[0]).intValue(),
                        ((Number) row[1]).intValue());
                label = key;
            }
        }
        BigDecimal days = row[3] instanceof BigDecimal b ? b : new BigDecimal(row[3].toString());
        return new SummaryRow(key, label, ((Number) row[2]).longValue(), MasterMapper.days(days),
                ((Number) row[4]).longValue(), ((Number) row[5]).longValue(), ((Number) row[6]).longValue());
    }
}
