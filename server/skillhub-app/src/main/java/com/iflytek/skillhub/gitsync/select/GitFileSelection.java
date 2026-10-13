package com.iflytek.skillhub.gitsync.select;

import java.util.List;

/**
 * Outcome of applying the package policy to one skill directory.
 *
 * @param selected repository blobs that will be imported
 * @param skipped  blobs that will be left out, with the reason, so the import
 *                 plan can tell an administrator what is being dropped
 */
public record GitFileSelection(List<GitTreeFile> selected, List<SkippedGitFile> skipped) {
}
