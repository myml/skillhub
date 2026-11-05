package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Batch request for the administrator skill console: the selected skill ids plus,
 * for a transfer, the target author.
 */
public record AdminSkillBatchRequest(
        @NotEmpty(message = "{validation.skill.batch.ids.notEmpty}")
        List<Long> skillIds,
        String toUserId
) {
}