package com.iflytek.skillhub.gitsync.dto;

import java.time.Instant;
import java.util.List;

/**
 * Dry-run import plan for a source.
 *
 * @param failing directories the next sync would fail to import
 * @param items   one entry per skill directory, including the reasons it would fail
 */
public record GitImportPlanResponse(Long sourceId,
                                    String ref,
                                    String commitSha,
                                    String subpath,
                                    Instant plannedAt,
                                    long added,
                                    long changed,
                                    long unchanged,
                                    long removed,
                                    long failing,
                                    List<Item> items) {

    public record Item(String itemPath,
                       String skillName,
                       String treeSha,
                       String changeType,
                       String previousTreeSha,
                       Long skillId,
                       int fileCount,
                       List<SkippedFile> skippedFiles,
                       List<String> problems) {
    }

    /**
     * A repository file the registry would not accept.
     */
    public record SkippedFile(String path, String reason) {
    }
}