package com.iflytek.skillhub.dto;

import java.util.List;

/**
 * Download statistics for a single skill over an inclusive day range.
 */
public record SkillDownloadStatsResponse(Long skillId,
                                         String from,
                                         String to,
                                         long total,
                                         List<DailyPoint> daily,
                                         List<DimensionCount> channels,
                                         List<DimensionCount> versions) {

    public record DailyPoint(String day, long count) {
    }

    public record DimensionCount(String key, long count) {
    }
}
