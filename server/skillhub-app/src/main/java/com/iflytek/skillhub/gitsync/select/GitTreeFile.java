package com.iflytek.skillhub.gitsync.select;

import com.iflytek.skillhub.gitsync.git.GitTreeEntry;

/**
 * A selected blob with its package-relative path.
 *
 * @param packagePath path relative to the skill directory, which is what
 *                    {@code SkillPublishService} expects
 */
public record GitTreeFile(String packagePath, GitTreeEntry entry) {
}
