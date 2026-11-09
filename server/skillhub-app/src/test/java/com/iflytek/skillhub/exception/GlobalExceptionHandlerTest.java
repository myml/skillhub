package com.iflytek.skillhub.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.gitsync.git.GitProviderException;
import com.iflytek.skillhub.gitsync.git.GitRefNotFoundException;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.security.SensitiveLogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private SensitiveLogSanitizer sensitiveLogSanitizer;

    @Mock
    private SkillHubMetrics metrics;

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("error.request.timeout", java.util.Locale.getDefault(), "Request timed out");
        ApiResponseFactory responseFactory = new ApiResponseFactory(
                messageSource,
                Clock.fixed(Instant.parse("2026-03-20T00:00:00Z"), ZoneOffset.UTC)
        );
        handler = new GlobalExceptionHandler(responseFactory, sensitiveLogSanitizer, metrics);
    }

    @Test
    void handleAsyncRequestTimeout_shouldReturnNoContentForSseRequests() {
        when(request.getRequestURI()).thenReturn("/api/v1/notifications/sse");

        ResponseEntity<?> response = handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void handleAsyncRequestTimeout_shouldReturnApiEnvelopeForNonSseRequests() {
        when(request.getRequestURI()).thenReturn("/api/v1/publish");
        when(request.getMethod()).thenReturn("POST");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn("/api/v1/publish");

        ResponseEntity<?> response = handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.code()).isEqualTo(408);
        assertThat(body.msg()).isEqualTo("Request timed out");
    }

    @Test
    void handleGitRefNotFound_shouldAnswerBadRequestWithTheBranchesThatExist() {
        GitRefNotFoundException ex = new GitRefNotFoundException("master", List.of("develop", "main"), "main");

        ResponseEntity<?> response = handler.handleGitRefNotFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(400);
        assertThat(body.details()).containsEntry("ref", "master");
        assertThat(body.details()).containsEntry("suggestion", "main");
        assertThat(body.details().get("availableBranches")).isEqualTo(List.of("develop", "main"));
    }

    @Test
    void handleGitRefNotFound_shouldNotOfferASuggestionTwiceWhenNoneIsClose() {
        GitRefNotFoundException ex = new GitRefNotFoundException("release/1.0", List.of("main"), null);

        ResponseEntity<?> response = handler.handleGitRefNotFound(ex, request);

        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.details()).doesNotContainKey("suggestion");
    }

    @Test
    void handleGitProviderException_shouldAnswerBadRequestWhenTheCredentialIsRejected() {
        when(request.getMethod()).thenReturn("POST");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn("/api/v1/admin/git-sources");

        ResponseEntity<?> response = handler.handleGitProviderException(new GitProviderException(
                "git ls-remote --symref failed: remote: Invalid username or token. "
                        + "fatal: Authentication failed for 'https://github.com/owner/repo/'"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(400);
        assertThat(body.msg()).isEqualTo("error.gitSource.credentialRejected");
        assertThat(body.details()).containsEntry("kind", "authentication");
    }

    @Test
    void handleGitProviderException_shouldAnswerBadRequestWhenTheRepositoryIsMissing() {
        when(request.getMethod()).thenReturn("POST");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn("/api/v1/admin/git-sources");

        ResponseEntity<?> response = handler.handleGitProviderException(new GitProviderException(
                "git ls-remote failed: remote: Repository not found."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.msg()).isEqualTo("error.gitSource.repoUnavailable");
        assertThat(body.details()).containsEntry("kind", "not_found");
    }

    @Test
    void handleGitProviderException_shouldAnswerBadGatewayInsteadOfUnhandled() {
        when(request.getMethod()).thenReturn("POST");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn("/api/v1/admin/git-sources/11/plan");

        ResponseEntity<?> response = handler.handleGitProviderException(
                new GitProviderException("git fetch --prune failed: fatal: couldn't find remote ref master"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(502);
        assertThat(body.details()).isNull();
    }
}
