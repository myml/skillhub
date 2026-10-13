package com.iflytek.skillhub.gitsync.scan;

import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One scan of a source: the pinned commit, the discovered skill directories,
 * and the previously observed state used for change detection.
 *
 * <p>Scanning performs read-only provider calls, so the executor reuses the same
 * scan for planning and importing instead of hitting the provider twice.
 */
public record GitSourceScan(Long sourceId,
                            String ref,
                            String commitSha,
                            String subpath,
                            List<DiscoveredSkill> skills,
                            Map<String, GitSourceItemRecord> previousItems) {

    public Optional<DiscoveredSkill> skill(String itemPath) {
        return skills.stream().filter(skill -> skill.itemPath().equals(itemPath)).findFirst();
    }
}
