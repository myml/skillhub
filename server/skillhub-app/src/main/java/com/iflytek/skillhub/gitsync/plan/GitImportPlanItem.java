package com.iflytek.skillhub.gitsync.plan;

import com.iflytek.skillhub.gitsync.select.SkippedGitFile;
import java.util.List;

/**
 * One skill directory in an import plan.
 *
 * @param changeType     comparison against the last observed state
 * @param previousTreeSha fingerprint recorded during the previous sync, when known
 * @param skillId        already imported skill, when known
 * @param fileCount      number of files that would be imported
 * @param skippedFiles   repository files the registry would not accept
 */
public record GitImportPlanItem(String itemPath,
                                String skillName,
                                String treeSha,
                                ChangeType changeType,
                                String previousTreeSha,
                                Long skillId,
                                int fileCount,
                                List<SkippedGitFile> skippedFiles) {

    public enum ChangeType {
        /** Never imported before. */
        ADDED,
        /** Directory content changed since the last sync. */
        CHANGED,
        /** Fingerprint unchanged; the next sync will skip it. */
        UNCHANGED,
        /** Previously imported, but no longer present in the repository. */
        REMOVED
    }
}
