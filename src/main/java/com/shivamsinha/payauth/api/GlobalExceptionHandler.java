package com.shivamsinha.payauth.api;

import com.shivamsinha.payauth.api.exception.AuthorizationNotFoundException;
import com.shivamsinha.payauth.api.exception.IdempotencyConflictException;
import com.shivamsinha.payauth.api.exception.InvalidStateTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every error leaves this service as RFC 7807 {@code application/problem+json}.
 *
 * <p>A payments client integrating against this API has to be able to tell
 * "your request was wrong" from "try again" from "this key is already in use"
 * without parsing prose, so the {@code type} URI is the stable machine-readable
 * discriminator and {@code detail} is for humans.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://payauth.shivamsinha.com/problems/";

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex,
                                             HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "missing-header",
                "Missing required header",
                "The '" + ex.getHeaderName() + "' header is required.",
                request);
        problem.setProperty("header", ex.getHeaderName());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest request) {
        Map<String, String> violations = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage(),
                        (first, second) -> first,
                        LinkedHashMap::new));

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "validation-failed",
                "Request validation failed",
                "One or more fields are invalid.",
                request);
        problem.setProperty("violations", violations);
        return problem;
    }

    /**
     * Raised by {@code @Validated} on method parameters, which is how the
     * Idempotency-Key header constraints are enforced.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex,
                                                   HttpServletRequest request) {
        Map<String, String> violations = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            violations.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "validation-failed",
                "Request validation failed",
                "One or more request parameters are invalid.",
                request);
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex,
                                              HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "malformed-request",
                "Malformed request body",
                "The request body could not be parsed as JSON matching the expected schema.",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                            HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "malformed-request",
                "Malformed path variable",
                "'" + ex.getName() + "' is not a valid value for this endpoint.",
                request);
    }

    /**
     * An unmapped path. Spring raises this for anything the dispatcher cannot route,
     * including {@code /} and {@code /favicon.ico}.
     *
     * <p>It needs an explicit handler for two reasons. It is a 404, not a 500 — the
     * request was fine, the path simply does not exist. And it must not be logged at
     * ERROR with a stack trace: unmapped paths are what every bot, scanner and
     * browser favicon request produces, and routing that noise to the same place as
     * genuine internal faults is how an on-call rotation learns to ignore its alerts.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return problem(
                HttpStatus.NOT_FOUND,
                "no-such-endpoint",
                "Endpoint not found",
                "No endpoint is mapped to " + request.getMethod() + " " + request.getRequestURI() + ".",
                request);
    }

    /**
     * The path exists but not for this verb. 405 must carry an {@code Allow} header,
     * which is why this returns a ResponseEntity rather than a bare ProblemDetail.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "method-not-allowed",
                "Method not allowed",
                ex.getMethod() + " is not supported by " + request.getRequestURI() + ".",
                request);

        Set<HttpMethod> allowed = ex.getSupportedHttpMethods() == null ? Set.of() : ex.getSupportedHttpMethods();
        problem.setProperty("supportedMethods", allowed.stream().map(HttpMethod::name).sorted().toList());

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(allowed.toArray(HttpMethod[]::new))
                .body(problem);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletRequest request) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported-media-type",
                "Unsupported media type",
                "This endpoint consumes application/json.",
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ProblemDetail handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                             HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_ACCEPTABLE,
                "not-acceptable",
                "Not acceptable",
                "This endpoint produces application/json.",
                request);
    }

    @ExceptionHandler(AuthorizationNotFoundException.class)
    public ProblemDetail handleNotFound(AuthorizationNotFoundException ex,
                                        HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "authorization-not-found",
                "Authorization not found",
                ex.getMessage(),
                request);
        problem.setProperty("authorizationId", ex.getAuthorizationId().toString());
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex,
                                                   HttpServletRequest request) {
        String slug = switch (ex.getReason()) {
            case REQUEST_MISMATCH -> "idempotency-key-reused";
            case CONCURRENT_REQUEST -> "idempotency-request-in-progress";
        };
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                slug,
                "Idempotency conflict",
                ex.getMessage(),
                request);
        problem.setProperty("reason", ex.getReason().name());
        // A concurrent duplicate is transient: the winner will finish shortly and the
        // same key will then replay cleanly, so we tell the client it is worth retrying.
        problem.setProperty("retryable", ex.getReason() == IdempotencyConflictException.Reason.CONCURRENT_REQUEST);
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex,
                                              HttpServletRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "concurrent-modification",
                "Concurrent modification",
                "The authorization was modified by another request. Re-read it and retry.",
                request);
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidStateTransitionException ex,
                                                 HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "invalid-state-transition",
                "Invalid state transition",
                ex.getMessage(),
                request);
    }

    /**
     * The catch-all. Everything reaching here is, by definition, a fault we did not
     * anticipate, so it is logged at ERROR with a stack trace and someone should look
     * at it. Keeping routine 404s and 405s out of this method is what makes that
     * statement true and the ERROR log worth reading.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Deliberately opaque to the caller: an internal failure must not leak stack
        // frames, SQL, or card data into a response body.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal server error",
                "The request could not be completed.",
                request);
    }

    private ProblemDetail problem(HttpStatus status,
                                  String slug,
                                  String title,
                                  String detail,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
