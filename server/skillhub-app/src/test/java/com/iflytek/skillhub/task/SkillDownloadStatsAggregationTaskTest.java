package com.iflytek.skillhub.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillDownloadStatsAggregationTaskTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:35:00Z");

    @Mock
    private SkillDownloadStatsRepository repository;

    private SkillDownloadStatsAggregationTask task() {
        return new SkillDownloadStatsAggregationTask(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rollupWindow_isAlignedToUtcDayBoundaries() {
        Instant[] window = SkillDownloadStatsAggregationTask.rollupWindow(NOW);

        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), window[0]);
        assertEquals(Instant.parse("2026-10-10T00:00:00Z"), window[1]);
    }

    @Test
    void rollupWindow_coversWholeDaysForAnyTimeOfDay() {
        Instant[] early = SkillDownloadStatsAggregationTask.rollupWindow(Instant.parse("2026-10-09T00:00:01Z"));
        Instant[] late = SkillDownloadStatsAggregationTask.rollupWindow(Instant.parse("2026-10-09T23:59:59Z"));

        assertEquals(early[0], late[0]);
        assertEquals(early[1], late[1]);
    }

    @Test
    void aggregateAndPrune_rollsUpWindowAndPrunesExpiredEvents() {
        given(repository.rollupDaily(
                Instant.parse("2026-10-06T00:00:00Z"),
                Instant.parse("2026-10-10T00:00:00Z"))).willReturn(3);
        given(repository.deleteEventsBefore(Instant.parse("2026-07-11T10:35:00Z"))).willReturn(5);

        task().aggregateAndPrune();

        then(repository).should().rollupDaily(
                Instant.parse("2026-10-06T00:00:00Z"),
                Instant.parse("2026-10-10T00:00:00Z"));
        then(repository).should().deleteEventsBefore(Instant.parse("2026-07-11T10:35:00Z"));
    }
}
