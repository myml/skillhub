package com.iflytek.skillhub.task;

import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds raw download events into the day-granularity rollup table and prunes
 * expired raw events.
 *
 * <p>The rollup window is aligned to UTC day boundaries and always covers whole
 * days, so re-running it replaces each day with a complete recount instead of
 * accumulating partial results.
 */
@Component
public class SkillDownloadStatsAggregationTask {

    private static final Logger log = LoggerFactory.getLogger(SkillDownloadStatsAggregationTask.class);

    static final int ROLLUP_WINDOW_DAYS = 3;
    static final int EVENT_RETENTION_DAYS = 90;

    private final SkillDownloadStatsRepository repository;
    private final Clock clock;

    public SkillDownloadStatsAggregationTask(SkillDownloadStatsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Runs every five minutes: the rollup is an idempotent upsert over a small
     * window, so a short cadence keeps the stats page within a few minutes of the
     * live counters instead of an hour behind.
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional
    public void aggregateAndPrune() {
        Instant now = Instant.now(clock);
        Instant[] window = rollupWindow(now);

        int rolledUp = repository.rollupDaily(window[0], window[1]);
        int pruned = repository.deleteEventsBefore(now.minus(EVENT_RETENTION_DAYS, ChronoUnit.DAYS));

        if (rolledUp > 0 || pruned > 0) {
            log.info("Download stats aggregation wrote {} rollup rows and pruned {} raw events", rolledUp, pruned);
        }
    }

    /**
     * Half-open instant window covering the last {@value #ROLLUP_WINDOW_DAYS} complete UTC days
     * plus the current one.
     */
    static Instant[] rollupWindow(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant from = today.minusDays(ROLLUP_WINDOW_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Instant[]{from, to};
    }
}
