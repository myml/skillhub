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
 * @param problems       reasons a sync would fail to import this directory; empty when it will import
 */
public record GitImportPlanItem(String itemPath,
                                String skillName,
                                String treeSha,
                                ChangeType changeType,
                                String previousTreeSha,
                                Long skillId,
                                int fileCount,
                                List<SkippedGitFile> skippedFiles,
                                List<PlanProblem> problems) {

    public GitImportPlanItem {
        skippedFiles = skippedFiles == null ? List.of() : List.copyOf(skippedFiles);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

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

    /**
     * Whether a sync would fail to import this directory.
     *
     * <p>An unchanged directory is not re-imported, so its problems only surface
     * when the content changes; it is still reported, because the dry-run is the
     * place to notice them.
     */
    public boolean willFail() {
        return !problems.isEmpty() && changeType != ChangeType.REMOVED;
    }
}