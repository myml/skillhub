package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Envelope every API response shares.
 *
 * <p>{@code details} carries machine-readable context for errors the caller can act
 * on, for example the branches a git repository actually has. It is left out of
 * the JSON whenever it is empty, so responses that do not need it keep their
 * existing shape.
 */
public record ApiResponse<T>(
        int code,
        String msg,
        T data,
        Instant timestamp,
        String requestId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details
) {

    public ApiResponse(int code, String msg, T data, Instant timestamp, String requestId) {
        this(code, msg, data, timestamp, requestId, null);
    }
}
