package com.iflytek.skillhub.gitsync.git;

/**
 * A git reference resolved to an immutable commit.
 */
public record GitCommitRef(String commitSha, String ref) {
}
