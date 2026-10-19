package com.vatsasarthak.notifyr.exception;

import com.vatsasarthak.notifyr.config.RequestSizeLimitFilter;
import com.vatsasarthak.notifyr.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler({NotificationNotFoundException.class, RecipientNotFoundException.class,
            TemplateNotFoundException.class, CampaignNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({DuplicateEmailException.class, DuplicateTemplateNameException.class,
            DuplicateUsernameException.class, CampaignAlreadySentException.class, CampaignNotEditableException.class,
            IdempotencyConflictException.class, InvalidNotificationStateException.class,
            DeadLetterNotReplayableException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler({UnsupportedChannelException.class, InvalidCampaignTargetException.class,
            InvalidScheduleTimeException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(AdminRegistrationNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleAdminRegistrationNotAllowed(AdminRegistrationNotAllowedException ex,
                                                                           HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    // constraints on request parameters/headers (e.g. Idempotency-Key length)
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
                                                                HttpServletRequest request) {
        // when a handler has constrained parameters, Spring reports @Valid body errors here as well
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(fieldError ->
                        errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage()));
            } else {
                RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
                String name = header != null && !header.value().isEmpty()
                        ? header.value() : result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error -> errors.putIfAbsent(name, error.getDefaultMessage()));
            }
        });
        return error(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        // a body without Content-Length that exceeded the limit while it was being read
        if (ex.getMostSpecificCause() instanceof RequestSizeLimitFilter.BodyTooLargeException tooLarge) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getMessage(), request);
        }
        return error(HttpStatus.BAD_REQUEST, "Malformed request body or invalid field value", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", request);
    }

    // unknown "sort" property in a paginated request
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleUnknownSortProperty(PropertyReferenceException ex,
                                                                   HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Cannot sort by '" + ex.getPropertyName() + "'", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "No endpoint " + request.getMethod() + " " + request.getRequestURI(), request);
    }

    // e.g. two concurrent requests creating the same unique username/email/template name
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "Request conflicts with existing data", request);
    }

    // anything unexpected: log the stack trace, but never return internals to the client
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Spring MVC's own exceptions (unsupported Content-Type, missing parameter, ...) carry their 4xx status;
        // without this they were reported as 500
        if (ex instanceof org.springframework.web.ErrorResponse mvcError) {
            int status = mvcError.getStatusCode().value();
            String detail = mvcError.getBody().getDetail();
            return ResponseEntity.status(status).headers(mvcError.getHeaders())
                    .body(ErrorResponse.of(status, detail != null ? detail : mvcError.getBody().getTitle(),
                            request.getRequestURI()));
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request) {
        return error(status, message, request, null);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request,
                                                       Map<String, String> details) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), message, request.getRequestURI(), details));
    }
}
