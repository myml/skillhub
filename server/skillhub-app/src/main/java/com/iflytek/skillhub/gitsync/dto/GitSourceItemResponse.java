package com.iflytek.skillhub.gitsync.dto;

import java.time.Instant;

/**
 * A skill directory discovered in a source repository.
 */
public record GitSourceItemResponse(String itemPath,
                                    String skillName,
                                    String treeSha,
                                    String commitSha,
                                    String state,
                                    Long skillId,
                                    Instant lastSeenAt,
                                    Instant lastSyncedAt) {
}
