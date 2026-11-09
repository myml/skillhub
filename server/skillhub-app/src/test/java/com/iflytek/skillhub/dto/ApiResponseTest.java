package com.iflytek.skillhub.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Adding {@code details} to the envelope must not change the shape of the
 * responses that do not use it: clients and contract tests already rely on the
 * five existing fields.
 */
class ApiResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    @Test
    void omitsDetailsWhenThereIsNothingToSay() throws Exception {
        String json = mapper.writeValueAsString(
                new ApiResponse<>(0, "ok", "payload", NOW, "req-1"));

        assertThat(json).doesNotContain("details");
        assertThat(json).contains("\"code\":0", "\"msg\":\"ok\"", "\"data\":\"payload\"", "\"requestId\":\"req-1\"");
    }

    @Test
    void includesDetailsWhenTheErrorCarriesContext() throws Exception {
        String json = mapper.writeValueAsString(new ApiResponse<>(400, "bad", null, NOW, "req-2",
                Map.of("ref", "master", "availableBranches", List.of("develop", "main"))));

        assertThat(json).contains("\"details\":{");
        assertThat(json).contains("\"ref\":\"master\"");
        assertThat(json).contains("\"availableBranches\":[\"develop\",\"main\"]");
    }
}
