package com.iflytek.skillhub.gitsync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registers a git repository as a skill source.
 *
 * <p>Neither the publisher nor the visibility of the imported skills is a caller
 * decision: the creator is the publisher, and visibility follows the target
 * namespace exactly like a manual publish does (GLOBAL is public, a team
 * namespace is visible to its members).
 *
 * @param defaultRef    branch, tag or commit to sync; empty follows the repository's
 *                      own default branch (the remote's HEAD)
 * @param subpath    repository subdirectory to scan; empty scans the whole repository
 * @param credential    credential for the repository: {@code <user>:<deploy token>} for
 *                      HTTP(S) remotes or the private key itself for SSH remotes;
 *                      empty fetches a public repository anonymously
 */
public record GitSourceCreateRequest(
        @NotBlank @Size(max = 512) String repoUrl,
        @Size(max = 128) String defaultRef,
        @Size(max = 512) String subpath,
        @NotBlank @Size(max = 64) String namespaceSlug,
        @Size(max = 8192) String credential,
        Integer syncIntervalMinutes,
        Boolean enabled
) {
}
