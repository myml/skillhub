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

    public long count(GitImportPlanItem.ChangeType changeType) {
        return items.stream().filter(item -> item.changeType() == changeType).count();
    }
}
