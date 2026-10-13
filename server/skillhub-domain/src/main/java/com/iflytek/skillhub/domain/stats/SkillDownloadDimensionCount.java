package com.iflytek.skillhub.domain.stats;

/**
 * Download count grouped by a dimension value (channel name or version id).
 */
public record SkillDownloadDimensionCount(String key, long count) {
}
