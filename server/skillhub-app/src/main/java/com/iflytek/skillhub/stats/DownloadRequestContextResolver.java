package com.iflytek.skillhub.stats;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Derives download statistics attributes from the ambient HTTP request and
 * security context.
 *
 * <p>The download event carries only skill and version identifiers, so channel,
 * actor, and client fingerprint are resolved here instead of changing the
 * publishing side. Everything degrades to {@code unknown} when no request is
 * bound to the current thread.
 */
@Component
public class DownloadRequestContextResolver {

    private static final String REQUEST_ATTRIBUTE_USER_ID = "userId";
    private static final String CLIENT_HASH_SALT = "skillhub:download-stats:v1";

    public DownloadRequestContext resolve() {
        ServletRequestAttributes attributes = currentRequestAttributes();
        String userAgent = header(attributes, "User-Agent");
        String referer = header(attributes, "Referer");
        String actorId = resolveActorId(attributes);
        String clientHash = hashClient(clientAddress(attributes), userAgent);
        return new DownloadRequestContext(resolveChannel(userAgent, referer), actorId, clientHash);
    }

    static String resolveChannel(String userAgent, String referer) {
        String ua = lower(userAgent);
        String ref = lower(referer);

        if (ref.contains("/api/v1/download") || ref.contains("/api/v1/resolve")
                || ua.contains("clawhub") || ua.contains("openclaw")) {
            return DownloadRequestContext.CHANNEL_COMPAT;
        }
        if (ua.contains("skillhub") || ua.contains("node") || ua.contains("bun")
                || ua.contains("curl") || ua.contains("wget") || ua.contains("python")
                || ua.contains("go-http-client")) {
            return DownloadRequestContext.CHANNEL_CLI;
        }
        if (ua.contains("mozilla") || ua.contains("webkit") || ua.contains("safari")
                || ua.contains("firefox") || ua.contains("edg/")) {
            return DownloadRequestContext.CHANNEL_WEB;
        }
        return DownloadRequestContext.CHANNEL_UNKNOWN;
    }

    static String hashClient(String address, String userAgent) {
        if ((address == null || address.isBlank()) && (userAgent == null || userAgent.isBlank())) {
            return null;
        }
        String material = (address == null ? "" : address) + '|' + (userAgent == null ? "" : userAgent)
                + '|' + CLIENT_HASH_SALT;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private String resolveActorId(ServletRequestAttributes attributes) {
        if (attributes != null) {
            Object attribute = attributes.getRequest().getAttribute(REQUEST_ATTRIBUTE_USER_ID);
            if (attribute instanceof String userId && !userId.isBlank()) {
                return userId;
            }
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PlatformPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private String clientAddress(ServletRequestAttributes attributes) {
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int separator = forwarded.indexOf(',');
            return separator > 0 ? forwarded.substring(0, separator).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private String header(ServletRequestAttributes attributes, String name) {
        return attributes == null ? null : attributes.getRequest().getHeader(name);
    }

    private static ServletRequestAttributes currentRequestAttributes() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes
                : null;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
