package io.github.danie1le.dropoff.common.web;

import io.github.danie1le.dropoff.common.DomainException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 Problem Details body with a stable {@code code} field (ticket #8). Domain
 * exceptions map by category; validation errors list each bad field; unexpected errors never leak internals.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String CODE = "code";
    private static final String ERRORS = "errors";
    private static final String TYPE_MISMATCH = "typeMismatch";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String INTERNAL_DETAIL = "An unexpected error occurred.";

    /** One invalid input: which field or parameter, and why. */
    record FieldViolation(String field, String message) {}

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        HttpStatus status = switch (ex.category()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(problem(status, ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(problem(status, INTERNAL_ERROR, INTERNAL_DETAIL));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(ApiExceptionHandler::violation)
                .toList();
        return validationFailed(violations, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    if (result instanceof ParameterErrors errors) {
                        return errors.getAllErrors().stream().map(ApiExceptionHandler::violation);
                    }
                    String parameterName = result.getMethodParameter().getParameterName();
                    String name = parameterName == null ? "parameter" : parameterName;
                    return result.getResolvableErrors().stream().map(error -> new FieldViolation(name, message(error)));
                })
                .toList();
        return validationFailed(violations, headers);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String propertyName = ex.getPropertyName();
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : propertyName == null ? "value" : propertyName;
        return validationFailed(List.of(new FieldViolation(name, mustBeValid(ex.getRequiredType()))), headers);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return validationFailed(List.of(new FieldViolation(ex.getParameterName(), "is required")), headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return badRequest("MALFORMED_REQUEST", "The request body could not be read.", headers, null);
    }

    /**
     * Framework errors (404 route, 405, 415 …) keep Spring's body but always get a {@code code}. Spring builds that
     * body inside {@code super}, so the code is added to the response it returns, not to the incoming {@code body}.
     * Framework 5xx errors (e.g. a response that can't be written) are logged and get the same {@code INTERNAL_ERROR}
     * code and fixed detail as {@link #handleUnexpected}, so Spring's detail never leaks internals.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Framework server error", ex);
        }
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail detail) {
            if (statusCode.is5xxServerError()) {
                detail.setDetail(INTERNAL_DETAIL);
                detail.setProperty(CODE, INTERNAL_ERROR);
            } else if (!hasCode(detail)) {
                HttpStatus resolved = HttpStatus.resolve(statusCode.value());
                detail.setProperty(CODE, resolved == null ? "ERROR" : resolved.name());
            }
        }
        return response;
    }

    private static ResponseEntity<Object> validationFailed(List<FieldViolation> violations, HttpHeaders headers) {
        return badRequest("VALIDATION_FAILED", "The request has invalid fields.", headers, violations);
    }

    private static ResponseEntity<Object> badRequest(
            String code, String detail, HttpHeaders headers, @Nullable List<FieldViolation> errors) {
        HttpStatus badRequest = HttpStatus.BAD_REQUEST;
        ProblemDetail problem = problem(badRequest, code, detail);
        if (errors != null) {
            problem.setProperty(ERRORS, errors);
        }
        return ResponseEntity.status(badRequest).headers(headers).body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String code, @Nullable String detail) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, detail == null ? status.getReasonPhrase() : detail);
        problem.setProperty(CODE, code);
        return problem;
    }

    private static boolean hasCode(ProblemDetail detail) {
        Map<String, @Nullable Object> properties = detail.getProperties();
        return properties != null && properties.containsKey(CODE);
    }

    /** A {@link FieldError} keeps its field; a class-level {@link ObjectError} is reported under its object name. */
    private static FieldViolation violation(ObjectError error) {
        return error instanceof FieldError fieldError
                ? violation(fieldError)
                : new FieldViolation(error.getObjectName(), message(error));
    }

    private static FieldViolation violation(FieldError error) {
        if (TYPE_MISMATCH.equals(error.getCode())) {
            @Nullable
            Class<?> requiredType = error.contains(TypeMismatchException.class)
                    ? error.unwrap(TypeMismatchException.class).getRequiredType()
                    : null;
            return new FieldViolation(error.getField(), mustBeValid(requiredType));
        }
        return new FieldViolation(error.getField(), message(error));
    }

    private static String mustBeValid(@Nullable Class<?> requiredType) {
        return "must be a valid " + (requiredType == null ? "value" : requiredType.getSimpleName());
    }

    private static String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null ? "is invalid" : message;
    }
}
