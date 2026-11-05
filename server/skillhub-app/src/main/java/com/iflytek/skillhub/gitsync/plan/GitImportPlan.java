package com.iflytek.skillhub.gitsync.plan;

import java.time.Instant;
import java.util.List;

/**
 * Dry-run result of scanning a source: what a sync would add, change, or remove.
 * Producing a plan has no side effects on the registry.
 */
public record GitImportPlan(Long sourceId,
                            String ref,
                            String commitSha,
                            String subpath,
                            List<GitImportPlanItem> items,
                            Instant plannedAt) {

    /**
     * Directories of one change type that a sync would actually carry out.
     *
     * <p>Predicted failures are excluded here and counted by {@link #failing()}, so
     * the buckets plus the failures add up to the number of planned directories.
     */
    public long count(GitImportPlanItem.ChangeType changeType) {
        return items.stream()
                .filter(item -> item.changeType() == changeType && !item.willFail())
                .count();
    }

    /** Directories a sync would fail to import, according to the prechecks. */
    public long failing() {
        return items.stream().filter(GitImportPlanItem::willFail).count();
    }
}
