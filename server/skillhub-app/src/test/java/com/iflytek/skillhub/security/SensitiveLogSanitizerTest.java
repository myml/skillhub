package com.iflytek.skillhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveLogSanitizerTest {

    private final SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();

    @Test
    void shouldRedactCredentialsInAJsonBody() {
        String body = "{\"repoUrl\":\"https://github.com/owner/repo\",\"credential\":\"git:glpat-secret\","
                + "\"defaultRef\":\"main\",\"password\":\"hunter2\"}";

        String sanitized = sanitizer.sanitizeBody(body);

        assertThat(sanitized).doesNotContain("glpat-secret");
        assertThat(sanitized).doesNotContain("hunter2");
        assertThat(sanitized).contains("\"credential\":\"***\"");
        assertThat(sanitized).contains("\"password\":\"***\"");
        assertThat(sanitized).contains("\"repoUrl\":\"https://github.com/owner/repo\"");
        assertThat(sanitized).contains("\"defaultRef\":\"main\"");
    }

    @Test
    void shouldLeaveABodyWithoutSecretsAlone() {
        String body = "{\"repoUrl\":\"https://github.com/owner/repo\",\"defaultRef\":\"\"}";

        assertThat(sanitizer.sanitizeBody(body)).isEqualTo(body);
    }

    @Test
    void shouldRedactSensitiveQueryParameters() {
        String sanitized = sanitizer.sanitizeQuery("returnTo=%2Fdashboard&token=abc123&password=secret&code=xyz");

        assertThat(sanitized).contains("returnTo=%2Fdashboard");
        assertThat(sanitized).contains("token=[REDACTED]");
        assertThat(sanitized).contains("password=[REDACTED]");
        assertThat(sanitized).contains("code=[REDACTED]");
    }
}
