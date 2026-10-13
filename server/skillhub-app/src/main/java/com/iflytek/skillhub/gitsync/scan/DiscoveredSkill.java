package com.iflytek.skillhub.gitsync.scan;

import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import java.util.List;

/**
 * A directory inside a repository that contains a {@code SKILL.md}.
 *
 * @param itemPath repository-relative directory path; empty string for the repository root
 * @param treeSha  fingerprint of the directory content, used to skip unchanged directories
 * @param files    every blob inside the directory (transitively), sorted by path
 */
public record DiscoveredSkill(String itemPath, String treeSha, List<GitTreeEntry> files) {
}
