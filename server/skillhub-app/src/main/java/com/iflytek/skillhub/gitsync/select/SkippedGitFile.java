package com.iflytek.skillhub.gitsync.select;

/**
 * A repository file that will not be imported.
 */
public record SkippedGitFile(String path, String reason) {
}
