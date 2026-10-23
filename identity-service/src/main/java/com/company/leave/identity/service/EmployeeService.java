package com.company.leave.identity.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse;
import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import com.company.leave.identity.dto.AuthDtos.ManagerRef;
import com.company.leave.identity.dto.AuthDtos.UserDto;
import com.company.leave.identity.dto.EmployeeDtos.EmployeeRequest;
import com.company.leave.identity.dto.EmployeeDtos.EmployeeResponse;
import com.company.leave.identity.dto.EmployeeDtos.InternalEmployee;
import com.company.leave.identity.repo.EmployeeRepository;
import com.company.leave.identity.repo.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EmployeeService {

    private static final Logger LOG = LoggerFactory.getLogger(EmployeeService.class);

    static final Set<String> SORT_FIELDS = Set.of("id", "employeeCode", "name", "email", "role", "department",
            "designation", "dateOfJoining", "active");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_HIERARCHY_DEPTH = 100;
    private static final int MAX_INTERNAL_IDS = 500;

    private final EmployeeRepository employees;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final LeaveBalanceClient leaveBalanceClient;

    public EmployeeService(EmployeeRepository employees, RefreshTokenRepository refreshTokens,
                           PasswordEncoder passwordEncoder, LeaveBalanceClient leaveBalanceClient) {
        this.employees = employees;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.leaveBalanceClient = leaveBalanceClient;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public ApiResponse<List<EmployeeResponse>> list(int page, int pageSize, String sort, String role,
                                                    String department, Boolean active, String q) {
        if (page < 1) {
            throw fieldError("page", "page must be >= 1");
        }
        if (pageSize < 1) {
            throw fieldError("pageSize", "pageSize must be >= 1");
        }
        int size = Math.min(pageSize, MAX_PAGE_SIZE);
        Sort order = parseSort(sort);
        Specification<Employee> spec = (root, query, cb) -> cb.conjunction();
        if (role != null && !role.isBlank()) {
            Role parsed = parseRole(role, "role");
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), parsed));
        }
        if (department != null && !department.isBlank()) {
            String dept = department.trim().toLowerCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("department")), dept));
        }
        if (active != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(root.get("employeeCode")), pattern, '\\')));
        }
        Page<Employee> result = employees.findAll(spec, PageRequest.of(page - 1, size, order));
        return ApiResponse.page(toResponses(result.getContent()), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(Long id) {
        return toResponse(load(id));
    }

    @Transactional(readOnly = true)
    public UserDto toUserDto(Employee e) {
        ManagerRef manager = null;
        if (e.getManagerId() != null) {
            manager = employees.findById(e.getManagerId()).map(EmployeeService::managerRef).orElse(null);
        }
        return userDto(e, manager);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public EmployeeResponse create(EmployeeRequest req) {
        if (req.password() == null || req.password().isBlank()) {
            throw fieldError("password", "password is required");
        }
        requireEncodable(req.password(), "password");
        String code = req.employeeCode().trim();
        String email = normalizeEmail(req.email());
        checkUnique(code, email, null);
        Role role = parseRole(req.role(), "role");
        validateManager(req.managerId(), null);

        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setEmail(email);
        e.setPasswordHash(passwordEncoder.encode(req.password()));
        apply(e, req, role);
        e.setActive(true);
        try {
            e = employees.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            LOG.info("Employee insert rejected by a constraint: {}", ex.getMostSpecificCause().getMessage());
            throw duplicate(List.of());
        }
        try {
            leaveBalanceClient.initBalances(e.getId(), e.getDateOfJoining());
        } catch (ApiException ex) {
            throw ex; // rolls back the insert
        } catch (RuntimeException ex) {
            LOG.error("Unexpected failure while initialising leave balances for employee {}", e.getId(), ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                    "Leave service is unavailable; the employee could not be provisioned");
        }
        return toResponse(e);
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest req) {
        Employee e = load(id);
        String code = req.employeeCode().trim();
        String email = normalizeEmail(req.email());
        checkUnique(code, email, id);
        Role role = parseRole(req.role(), "role");
        validateManager(req.managerId(), id);
        if (e.getRole() == Role.MANAGER && role != Role.MANAGER && employees.existsByManagerIdAndActiveTrue(id)) {
            throw ApiException.conflict("MANAGER_HAS_REPORTS",
                    "A manager with active direct reports cannot be changed to another role");
        }
        e.setEmployeeCode(code);
        e.setEmail(email);
        apply(e, req, role);
        boolean passwordReset = req.password() != null;
        if (passwordReset) {
            requireEncodable(req.password(), "password");
            e.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        try {
            e = employees.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            LOG.info("Employee update rejected by a constraint: {}", ex.getMostSpecificCause().getMessage());
            throw duplicate(List.of());
        }
        if (passwordReset) {
            refreshTokens.revokeAllForEmployee(id);
        }
        return toResponse(e);
    }

    @Transactional
    public EmployeeResponse setActive(Long id, boolean active, Long actingUserId) {
        Employee e = load(id);
        if (!active && e.getId().equals(actingUserId)) {
            throw ApiException.badRequest("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account");
        }
        e.setActive(active);
        e = employees.saveAndFlush(e);
        if (!active) {
            refreshTokens.revokeAllForEmployee(id);
        }
        return toResponse(e);
    }

    // ------------------------------------------------------------------ internal API

    @Transactional(readOnly = true)
    public InternalEmployee internalGet(Long id) {
        return internal(load(id));
    }

    @Transactional(readOnly = true)
    public List<InternalEmployee> internalHr() {
        return employees.findByRoleAndActiveTrueOrderByIdAsc(Role.HR).stream().map(EmployeeService::internal).toList();
    }

    @Transactional(readOnly = true)
    public List<InternalEmployee> internalByManager(Long managerId) {
        return employees.findByManagerIdAndActiveTrueOrderByIdAsc(managerId).stream()
                .map(EmployeeService::internal).toList();
    }

    @Transactional(readOnly = true)
    public List<InternalEmployee> internalByDepartment(String department) {
        return employees.findByDepartmentIgnoreCaseAndActiveTrueOrderByIdAsc(department.trim()).stream()
                .map(EmployeeService::internal).toList();
    }

    @Transactional(readOnly = true)
    public List<InternalEmployee> internalByIds(String idsCsv) {
        List<Long> ids = new ArrayList<>();
        for (String part : idsCsv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ex) {
                throw fieldError("ids", "ids must be a comma separated list of numeric ids");
            }
        }
        if (ids.size() > MAX_INTERNAL_IDS) {
            throw fieldError("ids", "at most " + MAX_INTERNAL_IDS + " ids may be requested");
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return employees.findAllById(ids).stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(EmployeeService::internal).toList();
    }

    // ------------------------------------------------------------------ helpers

    private Employee load(Long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee " + id + " not found"));
    }

    private void apply(Employee e, EmployeeRequest req, Role role) {
        e.setName(req.name().trim());
        e.setRole(role);
        e.setDepartment(req.department().trim());
        e.setDesignation(req.designation() == null || req.designation().isBlank() ? null : req.designation().trim());
        e.setDateOfJoining(req.dateOfJoining());
        e.setManagerId(req.managerId());
    }

    private void checkUnique(String code, String email, Long selfId) {
        List<ApiResponse.FieldError> details = new ArrayList<>();
        employees.findByEmployeeCodeIgnoreCase(code).filter(o -> !o.getId().equals(selfId))
                .ifPresent(o -> details.add(new ApiResponse.FieldError("employeeCode", "employeeCode already exists")));
        employees.findByEmailIgnoreCase(email).filter(o -> !o.getId().equals(selfId))
                .ifPresent(o -> details.add(new ApiResponse.FieldError("email", "email already exists")));
        if (!details.isEmpty()) {
            throw duplicate(details);
        }
    }

    private static ApiException duplicate(List<ApiResponse.FieldError> details) {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_EMPLOYEE",
                "An employee with this employee code or email already exists", details);
    }

    private void validateManager(Long managerId, Long selfId) {
        if (managerId == null) {
            return;
        }
        if (managerId.equals(selfId)) {
            throw fieldError("managerId", "An employee cannot be their own manager");
        }
        Employee manager = employees.findById(managerId)
                .orElseThrow(() -> fieldError("managerId", "Manager " + managerId + " does not exist"));
        if (!manager.isActive()) {
            throw fieldError("managerId", "Manager must be active");
        }
        if (manager.getRole() != Role.MANAGER) {
            throw fieldError("managerId", "Manager must have the MANAGER role");
        }
        if (selfId != null) {
            Long cursor = manager.getManagerId();
            int depth = 0;
            while (cursor != null && depth++ < MAX_HIERARCHY_DEPTH) {
                if (cursor.equals(selfId)) {
                    throw fieldError("managerId", "Manager assignment would create a reporting cycle");
                }
                cursor = employees.findById(cursor).map(Employee::getManagerId).orElse(null);
            }
        }
    }

    private static void requireEncodable(String password, String field) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw fieldError(field, field + " must be at most 72 bytes");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static Role parseRole(String value, String field) {
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw fieldError(field, field + " must be one of EMPLOYEE, MANAGER, HR");
        }
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.asc("id"));
        }
        String[] parts = sort.split(",", -1);
        String field = parts[0].trim();
        if (!SORT_FIELDS.contains(field) || parts.length > 2) {
            throw fieldError("sort", "sort field must be one of " + String.join(", ", new java.util.TreeSet<>(SORT_FIELDS)));
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String dir = parts[1].trim().toLowerCase(Locale.ROOT);
            if (dir.equals("desc")) {
                direction = Sort.Direction.DESC;
            } else if (!dir.equals("asc")) {
                throw fieldError("sort", "sort direction must be asc or desc");
            }
        }
        Sort primary = Sort.by(new Sort.Order(direction, field));
        return field.equals("id") ? primary : primary.and(Sort.by(Sort.Order.asc("id")));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static ApiException fieldError(String field, String message) {
        return ApiException.validation(message, List.of(new ApiResponse.FieldError(field, message)));
    }

    private static ManagerRef managerRef(Employee m) {
        return new ManagerRef(m.getId(), m.getEmployeeCode(), m.getName());
    }

    private static UserDto userDto(Employee e, ManagerRef manager) {
        return new UserDto(e.getId(), e.getEmployeeCode(), e.getName(), e.getEmail(), e.getRole().name(),
                e.getDepartment(), e.getDesignation(), e.getDateOfJoining(), manager, e.isActive());
    }

    private static InternalEmployee internal(Employee e) {
        return new InternalEmployee(e.getId(), e.getEmployeeCode(), e.getName(), e.getEmail(), e.getRole().name(),
                e.getDepartment(), e.getManagerId(), e.isActive());
    }

    private EmployeeResponse toResponse(Employee e) {
        return toResponses(List.of(e)).get(0);
    }

    /** Maps in bulk, resolving all referenced managers with one query. */
    private List<EmployeeResponse> toResponses(List<Employee> list) {
        Set<Long> managerIds = new HashSet<>();
        for (Employee e : list) {
            if (e.getManagerId() != null) {
                managerIds.add(e.getManagerId());
            }
        }
        Map<Long, ManagerRef> managers = new HashMap<>();
        if (!managerIds.isEmpty()) {
            employees.findAllById(managerIds).forEach(m -> managers.put(m.getId(), managerRef(m)));
        }
        return list.stream().map(e -> new EmployeeResponse(e.getId(), e.getEmployeeCode(), e.getName(), e.getEmail(),
                e.getRole().name(), e.getDepartment(), e.getDesignation(), e.getDateOfJoining(), e.getManagerId(),
                e.getManagerId() == null ? null : managers.get(e.getManagerId()), e.isActive(),
                e.getCreatedAt())).toList();
    }
}
