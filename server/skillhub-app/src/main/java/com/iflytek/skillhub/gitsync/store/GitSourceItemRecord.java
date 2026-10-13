package com.iflytek.skillhub.gitsync.store;

import java.time.Instant;

/**
 * One skill directory discovered in a repository.
 *
 * @param treeSha fingerprint of the directory content at the last observation
 * @param skillId target skill once it has been imported
 * @param state   {@code PENDING}, {@code IMPORTED}, {@code REMOVED}, or {@code ERROR}
 */
public record GitSourceItemRecord(Long id,
                                  Long sourceId,
                                  String itemPath,
                                  String skillName,
                                  String treeSha,
                                  String commitSha,
                                  Long skillId,
                                  String state,
                                  Instant lastSeenAt,
                                  Instant lastSyncedAt) {

    /** Discovered but not imported yet. */
    public static final String STATE_PENDING = "PENDING";
    /** Imported successfully; {@code skillId} is set. */
    public static final String STATE_IMPORTED = "IMPORTED";
    /** Import failed; the next sync retries it even when the fingerprint is unchanged. */
    public static final String STATE_ERROR = "ERROR";
    /** No longer present in the repository. */
    public static final String STATE_REMOVED = "REMOVED";
}
