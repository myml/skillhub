package com.iflytek.skillhub.gitsync.store;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
import java.time.Instant;

/**
 * Persisted git source configuration.
 *
 * @param subpath               repository subdirectory to scan, empty for the whole repository
 * @param credential            credential for HTTP(S) (user:token) or SSH (private key), null when public
 * @param publisherId           user id used as publisher when syncing, fixed to the creator
 * @param syncIntervalMinutes   advisory interval for scheduled syncs
 * @param defaultVisibility     visibility requested for newly imported skills
 */
public record GitSourceRecord(Long id,
                              String repoUrl,
                              String repoPath,
                              String defaultRef,
                              String subpath,
                              String namespaceSlug,
                              String credential,
                              String publisherId,
                              int syncIntervalMinutes,
                              boolean enabled,
                              SkillVisibility defaultVisibility,
                              String createdBy,
                              Instant createdAt,
                              Instant updatedAt,
                              Instant lastPlannedAt,
                              Instant lastSyncAt,
                              String lastSyncStatus,
                              String lastSyncMessage) {
}
