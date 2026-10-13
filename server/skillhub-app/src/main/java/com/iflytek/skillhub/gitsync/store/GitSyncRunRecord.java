package com.iflytek.skillhub.gitsync.store;

import java.time.Instant;

/**
 * One sync run, kept as history for the admin UI.
 *
 * @param triggerType {@code MANUAL} or {@code SCHEDULED}
 * @param status      {@code RUNNING}, {@code SUCCESS}, {@code PARTIAL}, or {@code FAILED}
 */
public record GitSyncRunRecord(Long id,
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
