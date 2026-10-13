package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.GitSyncRunRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitSyncSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @Mock
    private GitSourceStore store;

    @Mock
    private GitSyncExecutor executor;

    private GitSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GitSyncScheduler(store, executor);
    }

    private static GitSourceRecord source(long id) {
        return new GitSourceRecord(id, "https://github.com/owner/repo", "owner/repo",
                "main", "", "global", null, "admin-1", 60, true,
                com.iflytek.skillhub.domain.skill.SkillVisibility.PUBLIC, "admin-1", NOW, NOW, null, null, null, null);
    }

    private GitSyncRunRecord run(long sourceId) {
        return new GitSyncRunRecord(1L, sourceId, GitSyncExecutor.TRIGGER_SCHEDULED, "SUCCESS", 0, 0, 0, 0, 0, 0,
                null, NOW, NOW);
    }

    @Test
    void syncsEveryDueSource() {
        given(store.findDue()).willReturn(List.of(source(1L), source(2L)));
        given(executor.sync(eq(1L), any())).willReturn(run(1L));
        given(executor.sync(eq(2L), any())).willReturn(run(2L));

        scheduler.syncDueSources();

        then(executor).should().sync(1L, GitSyncExecutor.TRIGGER_SCHEDULED);
        then(executor).should().sync(2L, GitSyncExecutor.TRIGGER_SCHEDULED);
    }

    @Test
    void doesNothingWhenNoSourceIsDue() {
        given(store.findDue()).willReturn(List.of());

        scheduler.syncDueSources();

        then(executor).shouldHaveNoInteractions();
    }

    @Test
    void keepsSyncingRemainingSourcesWhenOneThrows() {
        given(store.findDue()).willReturn(List.of(source(1L), source(2L)));
        given(executor.sync(eq(1L), any())).willThrow(new IllegalStateException("provider exploded"));
        given(executor.sync(eq(2L), any())).willReturn(run(2L));

        assertDoesNotThrow(scheduler::syncDueSources);

        then(executor).should(times(1)).sync(2L, GitSyncExecutor.TRIGGER_SCHEDULED);
    }

    @Test
    void reportsStoreFailuresWithoutPropagating() {
        willThrow(new IllegalStateException("database is down")).given(store).findDue();

        assertDoesNotThrow(scheduler::syncDueSources);

        then(executor).shouldHaveNoInteractions();
    }

    @Test
    void releasesTheRunGuardAfterAFailureSoTheNextTickRetries() {
        willThrow(new IllegalStateException("database is down")).given(store).findDue();
        assertDoesNotThrow(scheduler::syncDueSources);

        willReturn(List.of(source(3L))).given(store).findDue();
        given(executor.sync(eq(3L), any())).willReturn(run(3L));

        scheduler.syncDueSources();

        then(executor).should().sync(3L, GitSyncExecutor.TRIGGER_SCHEDULED);
    }
}
