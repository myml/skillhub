package com.iflytek.skillhub.dto;

import java.util.List;

/**
 * Outcome of a batch operation. Failures are reported per skill so one rejected
 * row does not hide the successful ones.
 */
public record AdminSkillBatchResultResponse(
        List<Long> succeeded,
        List<Failure> failed
) {
    public record Failure(Long skillId, String reason) {
    }
}