package io.github.dennissimiyu.countryinfo.exception;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.validation.ConstraintViolationException;

/**
 * Maps every exception to an RFC 7807 problem response with the correct HTTP status.
 * <p>
 * Extending {@link ResponseEntityExceptionHandler} makes Spring MVC's own exceptions keep their
 * proper status codes (404 unknown path, 405 wrong method, 406, 415 wrong content type,
 * 400 malformed body / type mismatch / missing parameter, 413, 503 async timeout, ...)
 * instead of falling into the catch-all 500 handler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({ResourceNotFoundException.class, CountryNotFoundException.class})
    ResponseEntity<ProblemDetail> notFound(RuntimeException ex) {
        log.info("Not found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ResponseEntity<ProblemDetail> conflict(Exception ex) {
        log.warn("Data conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "The resource was modified concurrently or violates a constraint, please retry");
    }

    @ExceptionHandler(ExternalServiceException.class)
    ResponseEntity<ProblemDetail> external(ExternalServiceException ex) {
        log.error("External service failure: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /** Last resort. Still honours ErrorResponse / @ResponseStatus before defaulting to 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> generic(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            ProblemDetail body = er.getBody();
            enrich(body, HttpStatus.resolve(er.getStatusCode().value()));
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders()).body(body);
        }
        ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (rs != null) {
            String reason = rs.reason().isEmpty() ? ex.getMessage() : rs.reason();
            return problem(rs.code(), reason);
        }
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Reference: " + MDC.get("correlationId"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle("Validation failed");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Request failed status={} error={}", statusCode.value(), ex.getMessage(), ex);
        } else {
            log.info("Request rejected status={} error={}", statusCode.value(), ex.getMessage());
        }
        if (body == null) {
            body = ProblemDetail.forStatusAndDetail(statusCode, ex.getMessage());
        }
        if (body instanceof ProblemDetail pd) {
            enrich(pd, HttpStatus.resolve(statusCode.value()));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        enrich(pd, status);
        return ResponseEntity.status(status).body(pd);
    }

    private static void enrich(ProblemDetail pd, HttpStatus status) {
        if (pd.getTitle() == null && status != null) {
            pd.setTitle(status.getReasonPhrase());
        }
        pd.setProperty("correlationId", MDC.get("correlationId"));
    }
}
