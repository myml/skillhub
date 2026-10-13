package com.iflytek.skillhub.gitsync.dto;

import java.time.Instant;

/**
 * One sync run, as shown in the source history.
 */
public record GitSyncRunResponse(Long id,
                                 Long sourceId,
                                 String triggerType,
                                 String status,
                                 Integer plannedCount,
                                 Integer addedCount,
                                 Integer changedCount,
                                 Integer unchangedCount,
                                 Integer removedCount,
                                 Integer failedCount,
                                 String message,
                                 Instant startedAt,
                                 Instant finishedAt) {
}
