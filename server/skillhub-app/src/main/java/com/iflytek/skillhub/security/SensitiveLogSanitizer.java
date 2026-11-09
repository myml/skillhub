package com.iflytek.skillhub.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Applies lightweight redaction rules before sensitive strings are written to logs.
 */
@Component
public class SensitiveLogSanitizer {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "token", "authorization", "cookie",
            "secret", "api_key", "apikey", "access_key", "refresh_token", "code");

    /** JSON fields that carry a secret, e.g. the git source credential. */
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|access_token|refresh_token|secret"
                    + "|api_?key|access_key|credential|ssh_key|sshkey|private_key|deploy_token)\"\\s*:\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\")");

    /**
     * Masks the value of secret JSON fields in a request body. Bodies are logged
     * verbatim for audit purposes, and an administrator pasting a deploy token into
     * the git source form would otherwise leave it in plain text in the log file.
     */
    public String sanitizeBody(String body) {
        if (!StringUtils.hasText(body)) {
            return body;
        }
        return JSON_SECRET.matcher(body).replaceAll("$1\"***\"");
    }

    public String sanitizeRequestTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        if (!StringUtils.hasText(query)) {
            return uri;
        }
        return uri + "?" + sanitizeQuery(query);
    }

    String sanitizeQuery(String query) {
        return Arrays.stream(query.split("&"))
                .map(this::sanitizeQueryPart)
                .collect(Collectors.joining("&"));
    }

    private String sanitizeQueryPart(String queryPart) {
        int idx = queryPart.indexOf('=');
        if (idx < 0) {
            return queryPart;
        }
        String key = queryPart.substring(0, idx);
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEYS.contains(normalizedKey)) {
            return key + "=[REDACTED]";
        }
        return queryPart;
    }
}
