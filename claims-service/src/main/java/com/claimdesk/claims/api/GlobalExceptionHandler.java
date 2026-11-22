package com.claimdesk.claims.api;

import com.claimdesk.claims.domain.BusinessConflictException;
import com.claimdesk.claims.domain.InvalidTransitionException;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claimdesk.claims.application.TooManyAttemptsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends ResponseEntityExceptionHandler so Spring's own MVC errors (validation 400, unknown route 404,
 * wrong method 405...) keep their correct status; the catch-all below only sees what nothing else claims.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail invalidTransition(InvalidTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Invalid state transition", e.getMessage());
    }

    @ExceptionHandler(BusinessConflictException.class)
    ProblemDetail conflict(BusinessConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate() {
        return problem(HttpStatus.CONFLICT, "Concurrent update", "Claim was modified by someone else. Refresh and retry.");
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail notFound(EntityNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail badCredentials(BadCredentialsException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage());
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> tooManyAttempts(TooManyAttemptsException e) {
        // Deliberately generic: identical for real and unknown accounts.
        var pd = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts", "Too many login attempts. Try again later.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds())).body(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    /** Real bugs and infrastructure failures: log the detail, tell the client nothing internal. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
