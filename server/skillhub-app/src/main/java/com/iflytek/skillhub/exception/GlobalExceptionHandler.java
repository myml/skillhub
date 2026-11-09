package com.iflytek.skillhub.exception;

import com.iflytek.skillhub.auth.exception.AuthFlowException;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import com.iflytek.skillhub.domain.shared.exception.LocalizedMessage;
import com.iflytek.skillhub.gitsync.git.GitProviderException;
import com.iflytek.skillhub.gitsync.git.GitRefNotFoundException;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.security.SensitiveLogSanitizer;
import com.iflytek.skillhub.storage.StorageAccessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * Translates application, domain, auth, and infrastructure exceptions into the platform's JSON API
 * error envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ApiResponseFactory apiResponseFactory;
    private final SensitiveLogSanitizer sensitiveLogSanitizer;
    private final SkillHubMetrics metrics;

    public GlobalExceptionHandler(ApiResponseFactory apiResponseFactory,
                                  SensitiveLogSanitizer sensitiveLogSanitizer,
                                  SkillHubMetrics metrics) {
        this.apiResponseFactory = apiResponseFactory;
        this.sensitiveLogSanitizer = sensitiveLogSanitizer;
        this.metrics = metrics;
    }

    @ExceptionHandler(LocalizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocalizedError(LocalizedException ex, HttpServletRequest request) {
        return renderLocalizedError(ex, ex.status(), request);
    }

    @ExceptionHandler(AuthFlowException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthFlowException(AuthFlowException ex, HttpServletRequest request) {
        return renderLocalizedError(ex, ex.getStatus(), request);
    }

    @ExceptionHandler(LocalizedDomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocalizedDomainException(LocalizedDomainException ex, HttpServletRequest request) {
        return renderLocalizedError(ex, HttpStatus.valueOf(ex.statusCode()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElseGet(() -> ex.getBindingResult().getAllErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage())
                        .orElse(null));
        logHandledException(HttpStatus.BAD_REQUEST, "validation.request.invalid", request);
        if (msg == null || msg.isBlank()) {
            return ResponseEntity.badRequest().body(apiResponseFactory.error(400, "error.badRequest"));
        }
        return ResponseEntity.badRequest().body(apiResponseFactory.errorMessage(400, msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        logHandledException(HttpStatus.BAD_REQUEST, "error.badRequest", request);
        return ResponseEntity.badRequest().body(
                apiResponseFactory.error(400, "error.badRequest"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(SecurityException ex, HttpServletRequest request) {
        logHandledException(HttpStatus.FORBIDDEN, "error.forbidden", request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                apiResponseFactory.error(403, "error.forbidden"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        logHandledException(HttpStatus.FORBIDDEN, "error.forbidden", request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                apiResponseFactory.error(403, "error.forbidden"));
    }

    @ExceptionHandler(StorageAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleStorageAccess(StorageAccessException ex, HttpServletRequest request) {
        metrics.incrementStorageAccessFailure(ex.getOperation());
        logger.warn(
                "Object storage unavailable [requestId={}, method={}, path={}, userId={}, operation={}, key={}]",
                MDC.get("requestId"),
                request.getMethod(),
                sensitiveLogSanitizer.sanitizeRequestTarget(request),
                resolveUserId(request),
                ex.getOperation(),
                ex.getKey(),
                ex
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                apiResponseFactory.error(503, "error.storage.unavailable"));
    }

    /**
     * The configured branch, tag or commit does not exist in the repository.
     *
     * <p>A configuration mistake is not a server fault: answer 400 with the refs
     * the repository actually advertises, so the administrator can fix the form
     * instead of reading a generic 500.
     */
    @ExceptionHandler(GitRefNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleGitRefNotFound(GitRefNotFoundException ex,
                                                                  HttpServletRequest request) {
        logHandledException(HttpStatus.BAD_REQUEST, ex.messageCode(), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                apiResponseFactory.errorWithDetails(400, ex.messageCode(), ex.details(), ex.messageArgs()));
    }

    /**
     * git itself reported a failure we did not classify: the remote is unreachable,
     * the network is failing, or the runtime has no git binary.
     *
     * <p>That is a gateway problem rather than "unhandled": answer 502 with a
     * correlation id, and keep the stack trace out of the error log so a real bug in
     * our code still stands out. The git output goes to the debug log only, because
     * stderr can echo a remote URL that carries a credential.
     */
    @ExceptionHandler(GitProviderException.class)
    public ResponseEntity<ApiResponse<Void>> handleGitProviderException(GitProviderException ex,
                                                                        HttpServletRequest request) {
        logger.warn(
                "Git upstream failure [requestId={}, method={}, path={}, userId={}]: {}",
                MDC.get("requestId"),
                request.getMethod(),
                sensitiveLogSanitizer.sanitizeRequestTarget(request),
                resolveUserId(request),
                ex.getMessage()
        );
        logger.debug("Git upstream failure detail", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                apiResponseFactory.error(502, "error.gitSource.upstream"));
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<?> handleAsyncRequestTimeout(AsyncRequestTimeoutException ex, HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.endsWith("/sse")) {
            logger.debug("SSE timeout [requestId={}, path={}]", MDC.get("requestId"), path);
            return ResponseEntity.noContent().build();
        }

        logHandledException(HttpStatus.REQUEST_TIMEOUT, "error.request.timeout", request);
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(
                apiResponseFactory.error(408, "error.request.timeout"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(Exception ex, HttpServletRequest request) {
        logger.error(
                "Unhandled API exception [requestId={}, method={}, path={}, userId={}]",
                MDC.get("requestId"),
                request.getMethod(),
                sensitiveLogSanitizer.sanitizeRequestTarget(request),
                resolveUserId(request),
                ex
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                apiResponseFactory.error(500, "error.internal"));
    }

    private void logHandledException(HttpStatus status, String messageCode, HttpServletRequest request) {
        logger.info(
                "API request failed [requestId={}, status={}, method={}, path={}, userId={}, code={}]",
                MDC.get("requestId"),
                status.value(),
                request.getMethod(),
                sensitiveLogSanitizer.sanitizeRequestTarget(request),
                resolveUserId(request),
                messageCode
        );
    }

    private ResponseEntity<ApiResponse<Void>> renderLocalizedError(LocalizedMessage error,
                                                                   HttpStatus status,
                                                                   HttpServletRequest request) {
        logHandledException(status, error.messageCode(), request);
        return ResponseEntity.status(status).body(
                apiResponseFactory.error(status.value(), error.messageCode(), error.messageArgs()));
    }

    private String resolveUserId(HttpServletRequest request) {
        if (!(request.getUserPrincipal() instanceof Authentication authentication)) {
            return "anonymous";
        }
        if (authentication.getPrincipal() instanceof PlatformPrincipal principal) {
            return principal.userId();
        }
        return authentication.getName();
    }
}
