package com.iflytek.skillhub.gitsync.store;

import java.time.Instant;

/**
 * Provenance of a skill: which repository, ref, commit, and subdirectory it came from.
 */
public record SkillSourceLinkRecord(Long id,
                                    Long skillId,
                                    Long sourceId,
                                    String repoUrl,
                                    String itemPath,
                                    String ref,
                                    String commitSha,
                                    String treeSha,
                                    Instant syncedAt) {
}
