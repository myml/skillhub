package com.iflytek.skillhub.gitsync.dto;

import java.time.Instant;

/**
 * Git source as returned to the administration UI.
 *
 * <p>{@code defaultRef} is empty when the source follows the repository's own
 * default branch rather than a configured one.
 */
public record GitSourceResponse(Long id,
                                String repoUrl,
                                String webUrl,
                                String repoPath,
                                String defaultRef,
                                String subpath,
                                String namespaceSlug,
                                int importedSkillCount,
                                boolean hasCredential,
                                String credentialKind,
                                String publisherId,
                                int syncIntervalMinutes,
                                boolean enabled,
                                String defaultVisibility,
                                String createdBy,
                                Instant createdAt,
                                Instant updatedAt,
                                Instant lastPlannedAt,
                                Instant lastSyncAt,
                                String lastSyncStatus,
                                String lastSyncMessage) {
}
