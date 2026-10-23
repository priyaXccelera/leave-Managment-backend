package com.company.leave.leaves.web;

import com.company.leave.common.api.ApiException;
import com.company.leave.common.api.ApiResponse;
import com.company.leave.common.api.ApiResponse.FieldError;
import com.company.leave.common.security.AuthUser;
import com.company.leave.common.security.CurrentUser;
import com.company.leave.leaves.domain.Enums.HalfDaySession;
import com.company.leave.leaves.dto.LeaveRequestDtos.ActionRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.ApplyRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.CancelRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.LeaveRequestDto;
import com.company.leave.leaves.dto.LeaveRequestDtos.PreviewRequest;
import com.company.leave.leaves.dto.LeaveRequestDtos.PreviewResponse;
import com.company.leave.leaves.service.ApprovalService;
import com.company.leave.leaves.service.LeaveRequestQueryService;
import com.company.leave.leaves.service.LeaveRequestQueryService.AttachmentFile;
import com.company.leave.leaves.service.LeaveRequestService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/leave-requests")
public class LeaveRequestController {

    private final LeaveRequestService service;
    private final LeaveRequestQueryService queries;
    private final ApprovalService approvals;
    private final Validator validator;

    public LeaveRequestController(LeaveRequestService service, LeaveRequestQueryService queries,
                                  ApprovalService approvals, Validator validator) {
        this.service = service;
        this.queries = queries;
        this.approvals = approvals;
        this.validator = validator;
    }

    @PostMapping("/preview")
    public ApiResponse<PreviewResponse> preview(@Valid @RequestBody PreviewRequest request) {
        return ApiResponse.ok(service.preview(CurrentUser.get(), request));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LeaveRequestDto> applyJson(@RequestBody(required = false) ApplyRequest request) {
        AuthUser user = CurrentUser.get();
        return ApiResponse.ok(service.apply(user, validated(request), null));
    }

    /**
     * Multipart variant: either a JSON part {@code data} or flat form fields, plus an optional
     * {@code attachment} file part.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LeaveRequestDto> applyMultipart(
            @RequestPart(value = "data", required = false) ApplyRequest data,
            @RequestParam(value = "leaveTypeId", required = false) Long leaveTypeId,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate toDate,
            @RequestParam(value = "halfDay", required = false) Boolean halfDay,
            @RequestParam(value = "halfDaySession", required = false) HalfDaySession halfDaySession,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "contactNumber", required = false) String contactNumber,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment) {
        AuthUser user = CurrentUser.get();
        ApplyRequest request = data != null ? data
                : new ApplyRequest(leaveTypeId, fromDate, toDate, Boolean.TRUE.equals(halfDay), halfDaySession,
                reason, contactNumber);
        return ApiResponse.ok(service.apply(user, validated(request), attachment));
    }

    @GetMapping("/me")
    public ApiResponse<List<LeaveRequestDto>> mine(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long leaveTypeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort) {
        return paged(queries.myRequests(CurrentUser.get(), status, leaveTypeId, from, to, page, pageSize, sort));
    }

    @GetMapping("/pending-for-manager")
    public ApiResponse<List<LeaveRequestDto>> pendingForManager(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort) {
        AuthUser user = CurrentUser.requireRole("MANAGER");
        return paged(queries.pendingForManager(user, page, pageSize, sort));
    }

    @GetMapping("/team")
    public ApiResponse<List<LeaveRequestDto>> team(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort) {
        AuthUser user = CurrentUser.requireRole("MANAGER");
        return paged(queries.team(user, status, from, to, page, pageSize, sort));
    }

    @GetMapping("/pending-for-hr")
    public ApiResponse<List<LeaveRequestDto>> pendingForHr(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort) {
        AuthUser user = CurrentUser.requireRole("HR");
        return paged(queries.pendingForHr(user, page, pageSize, sort));
    }

    @GetMapping
    public ApiResponse<List<LeaveRequestDto>> all(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Long leaveTypeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort) {
        AuthUser user = CurrentUser.requireRole("HR");
        return paged(queries.all(user, status, employeeId, department, leaveTypeId, from, to, page, pageSize,
                sort));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<LeaveRequestDto> get(@PathVariable Long id) {
        return ApiResponse.ok(queries.detail(CurrentUser.get(), id));
    }

    @GetMapping("/{id:\\d+}/attachment")
    public ResponseEntity<Resource> attachment(@PathVariable Long id) {
        AttachmentFile file = queries.attachment(CurrentUser.get(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.resource());
    }

    @PostMapping("/{id:\\d+}/cancel")
    public ApiResponse<LeaveRequestDto> cancel(@PathVariable Long id,
                                               @RequestBody(required = false) @Valid CancelRequest request) {
        return ApiResponse.ok(service.cancel(CurrentUser.get(), id, request));
    }

    @PostMapping("/{id:\\d+}/manager-action")
    public ApiResponse<LeaveRequestDto> managerAction(@PathVariable Long id,
                                                      @Valid @RequestBody ActionRequest request) {
        AuthUser user = CurrentUser.requireRole("MANAGER");
        return ApiResponse.ok(approvals.managerAction(user, id, request));
    }

    @PostMapping("/{id:\\d+}/hr-action")
    public ApiResponse<LeaveRequestDto> hrAction(@PathVariable Long id, @Valid @RequestBody ActionRequest request) {
        AuthUser user = CurrentUser.requireRole("HR");
        return ApiResponse.ok(approvals.hrAction(user, id, request));
    }

    private static ApiResponse<List<LeaveRequestDto>> paged(Page<LeaveRequestDto> page) {
        return ApiResponse.page(page.getContent(), page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    /** Bean-validates the request (needed for multipart input) and reports violations as 400 with details. */
    private ApplyRequest validated(ApplyRequest request) {
        if (request == null) {
            throw ApiException.validation("Request body is required",
                    List.of(new FieldError("body", "must not be null")));
        }
        Set<ConstraintViolation<ApplyRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<FieldError> details = violations.stream()
                    .sorted(Comparator.comparing((ConstraintViolation<ApplyRequest> v) -> v.getPropertyPath()
                            .toString()))
                    .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                    .toList();
            throw ApiException.validation("Validation failed", details);
        }
        return request;
    }
}
