package com.iflytek.skillhub.controller.admin;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.gitsync.GitSyncAdminService;
import com.iflytek.skillhub.gitsync.dto.GitSourceCreateRequest;
import com.iflytek.skillhub.gitsync.git.GitRefNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

/**
 * The controller verifies the ref before it delegates to the service: a mistyped
 * branch has to be reported on the form, before anything is stored.
 */
@ExtendWith(MockitoExtension.class)
class GitSourceAdminControllerTest {

    private static final GitSourceCreateRequest REQUEST = new GitSourceCreateRequest(
            "https://github.com/owner/repo", "master", "", "global", "", 60, null);

    private static final PlatformPrincipal ADMIN = new PlatformPrincipal(
            "admin-1", "Admin", "admin@example.com", null, "local", Set.of("SUPER_ADMIN"));

    private static final PlatformPrincipal PLAIN_USER = new PlatformPrincipal(
            "user-1", "User", "user@example.com", null, "local", Set.of());

    @Mock
    private GitSyncAdminService service;

    @Mock
    private HttpServletRequest httpRequest;

    private GitSourceAdminController controller;

    @BeforeEach
    void setUp() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("response.success.created", Locale.getDefault(), "Created");
        messageSource.addMessage("response.success.updated", Locale.getDefault(), "Updated");
        ApiResponseFactory responseFactory = new ApiResponseFactory(
                messageSource, Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC));
        controller = new GitSourceAdminController(responseFactory, service);
    }

    @Test
    void createVerifiesTheRefBeforeTheSourceIsStored() {
        given(service.create(any(), any(), any())).willReturn(null);

        controller.createSource(REQUEST, ADMIN, httpRequest);

        InOrder order = inOrder(service);
        order.verify(service).preflightCreate(REQUEST);
        order.verify(service).create(eq(REQUEST), eq("admin-1"), any());
    }

    @Test
    void aMissingBranchPreventsTheSourceFromBeingStored() {
        willThrow(new GitRefNotFoundException("master", List.of("main"), "main"))
                .given(service).preflightCreate(REQUEST);

        assertThrows(GitRefNotFoundException.class,
                () -> controller.createSource(REQUEST, ADMIN, httpRequest));
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void updateVerifiesTheRefWithTheCallersRights() {
        given(service.update(any(), any(), any(), anyBoolean(), any())).willReturn(null);

        ApiResponse<?> response = controller.updateSource(7L, REQUEST, ADMIN, httpRequest);

        verify(service).preflightUpdate(7L, REQUEST, "admin-1", true);
        assertNull(response.data());
        verify(service).update(eq(7L), eq(REQUEST), eq("admin-1"), eq(true), any());
    }

    @Test
    void updateGivesAPlainUserTheLimitedScope() {
        given(service.update(any(), any(), any(), anyBoolean(), any())).willReturn(null);

        controller.updateSource(7L, REQUEST, PLAIN_USER, httpRequest);

        verify(service).preflightUpdate(7L, REQUEST, "user-1", false);
    }
}
