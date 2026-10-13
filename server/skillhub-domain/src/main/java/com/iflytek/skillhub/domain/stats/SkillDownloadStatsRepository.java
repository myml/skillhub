package com.iflytek.skillhub.domain.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Domain repository contract for skill download statistics.
 *
 * <p>Raw events are appended on every public download and later folded into a
 * day-granularity rollup table. Rollups recompute a whole day window, so running
 * them repeatedly is idempotent and safe after downtime or restores.
 */
public interface SkillDownloadStatsRepository {

    /**
     * Appends a single download event.
     */
    void recordEvent(Long skillId,
                     Long versionId,
                     String channel,
                     String actorId,
                     String clientHash,
                     Instant occurredAt);

    /**
     * Recomputes the daily rollup for the half-open instant window.
     *
     * @return number of rollup rows written
     */
    int rollupDaily(Instant from, Instant to);

    /**
     * Deletes raw events older than the threshold.
     *
     * @return number of deleted events
     */
    int deleteEventsBefore(Instant threshold);

    /**
     * Total downloads of a skill over the inclusive day range.
     */
    long countDownloads(Long skillId, LocalDate from, LocalDate to);

    List<SkillDownloadDailyPoint> findDailySeries(Long skillId, LocalDate from, LocalDate to);

    List<SkillDownloadDimensionCount> countByChannel(Long skillId, LocalDate from, LocalDate to);

    List<SkillDownloadDimensionCount> countByVersion(Long skillId, LocalDate from, LocalDate to);

    List<SkillDownloadTopSkill> findTopPublicSkills(LocalDate from, LocalDate to, int limit);
}
