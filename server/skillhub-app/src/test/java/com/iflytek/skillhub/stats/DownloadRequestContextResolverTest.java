package com.iflytek.skillhub.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class DownloadRequestContextResolverTest {

    private final DownloadRequestContextResolver resolver = new DownloadRequestContextResolver();

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveChannel_detectsCompatClientFromCompatReferer() {
        assertEquals(DownloadRequestContext.CHANNEL_COMPAT,
                DownloadRequestContextResolver.resolveChannel("node-fetch/1.0", "https://hub.example/api/v1/download/demo"));
    }

    @Test
    void resolveChannel_detectsCliClientFromUserAgent() {
        assertEquals(DownloadRequestContext.CHANNEL_CLI,
                DownloadRequestContextResolver.resolveChannel("skillhub-cli/0.1.13", null));
        assertEquals(DownloadRequestContext.CHANNEL_CLI,
                DownloadRequestContextResolver.resolveChannel("curl/8.5.0", null));
    }

    @Test
    void resolveChannel_detectsBrowserClient() {
        assertEquals(DownloadRequestContext.CHANNEL_WEB,
                DownloadRequestContextResolver.resolveChannel("Mozilla/5.0 (X11; Linux x86_64) Firefox/128.0", null));
    }

    @Test
    void resolveChannel_fallsBackToUnknown() {
        assertEquals(DownloadRequestContext.CHANNEL_UNKNOWN,
                DownloadRequestContextResolver.resolveChannel(null, null));
    }

    @Test
    void hashClient_isStableAndDoesNotLeakInput() {
        String first = DownloadRequestContextResolver.hashClient("10.0.0.7", "skillhub-cli/0.1.13");
        String second = DownloadRequestContextResolver.hashClient("10.0.0.7", "skillhub-cli/0.1.13");
        String other = DownloadRequestContextResolver.hashClient("10.0.0.8", "skillhub-cli/0.1.13");

        assertNotNull(first);
        assertEquals(first, second);
        assertNotEquals(first, other);
        assertEquals(64, first.length());
    }

    @Test
    void hashClient_returnsNullWithoutMaterial() {
        assertNull(DownloadRequestContextResolver.hashClient(null, null));
        assertNull(DownloadRequestContextResolver.hashClient("", " "));
    }

    @Test
    void resolve_readsChannelActorAndForwardedAddressFromRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "skillhub-cli/0.1.13");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new PlatformPrincipal("user-1", "User One", "user@example.com", null, "github", Set.of("USER")),
                null
        ));

        DownloadRequestContext context = resolver.resolve();

        assertEquals(DownloadRequestContext.CHANNEL_CLI, context.channel());
        assertEquals("user-1", context.actorId());
        assertEquals(DownloadRequestContextResolver.hashClient("203.0.113.9", "skillhub-cli/0.1.13"), context.clientHash());
    }

    @Test
    void resolve_prefersRequestUserIdAttributeOverPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "attribute-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("attribute-user", resolver.resolve().actorId());
    }

    @Test
    void resolve_withoutRequestContext_returnsUnknown() {
        assertEquals(DownloadRequestContext.unknown(), resolver.resolve());
    }
}
