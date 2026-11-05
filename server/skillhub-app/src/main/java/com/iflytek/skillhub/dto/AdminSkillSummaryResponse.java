package com.iflytek.skillhub.dto;

import java.time.Instant;

/**
 * One row of the administrator skill console.
 */
public record AdminSkillSummaryResponse(
        Long id,
        String namespaceSlug,
        String slug,
        String displayName,
        String ownerId,
        String ownerDisplayName,
        String visibility,
        String status,
        boolean hidden,
        boolean published,
        long downloadCount,
        Instant createdAt,
        Instant updatedAt
) {
}