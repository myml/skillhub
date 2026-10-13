package com.iflytek.skillhub.gitsync.dto;

import java.time.Instant;

/**
 * Provenance of a skill, shown on the skill detail page.
 *
 * <p>The commit is exposed as a plain SHA: the registry does not assume any
 * hosting software's commit-URL layout.
 */
public record SkillSourceResponse(String repoUrl,
                                  String webUrl,
                                  String itemPath,
                                  String ref,
                                  String commitSha,
                                  String treeSha,
                                  Instant syncedAt) {
}
