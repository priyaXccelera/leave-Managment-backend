package com.company.leave.common.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Single place translating every failure into the JSON error envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalid(MethodArgumentNotValidException ex) {
        return validation(ex.getBindingResult().getFieldErrors());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        return validation(ex.getBindingResult().getFieldErrors());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
        List<ApiResponse.FieldError> details = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new ApiResponse.FieldError(
                                r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .collect(Collectors.toList());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        List<ApiResponse.FieldError> details = ex.getConstraintViolations().stream()
                .map(v -> new ApiResponse.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .collect(Collectors.toList());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "Request body is missing or malformed", List.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissing(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message,
                List.of(new ApiResponse.FieldError(ex.getName(), message)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Uploaded file is too large", List.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity<ApiResponse<Void>> response = build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Method " + ex.getMethod() + " is not allowed for this resource", List.of());
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            headers.set(HttpHeaders.ALLOW, ex.getSupportedHttpMethods().stream()
                    .map(HttpMethod::name).collect(Collectors.joining(", ")));
        } else {
            headers.set(HttpHeaders.ALLOW, "GET");
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content type is not supported", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return build(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Requested media type is not acceptable", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        LOG.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", List.of());
    }

    private ResponseEntity<ApiResponse<Void>> validation(List<FieldError> fieldErrors) {
        List<ApiResponse.FieldError> details = fieldErrors.stream()
                .map(e -> new ApiResponse.FieldError(e.getField(), e.getDefaultMessage()))
                .collect(Collectors.toList());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", details);
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, String code, String message,
                                                    List<ApiResponse.FieldError> details) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, message, details));
    }
}
