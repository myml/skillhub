package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic synchronization of enabled git sources.
 *
 * <p>Sources are selected in the database ({@code last_sync_at} plus
 * {@code sync_interval_minutes}), so a restart cannot cause a source to be
 * missed or synced twice. Runs are serialized: a tick that finds a sync still
 * in flight is skipped rather than queued, so a slow repository cannot pile up
 * overlapping syncs.
 */
@Component
public class GitSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitSyncScheduler.class);

    private final GitSourceStore store;
    private final GitSyncExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GitSyncScheduler(GitSourceStore store, GitSyncExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    @Scheduled(cron = "0 */5 * * * ?")
    public void syncDueSources() {
        if (!running.compareAndSet(false, true)) {
            log.info("Skipping git sync tick: a previous run is still in progress");
            return;
        }
        try {
            List<GitSourceRecord> due;
            try {
                due = store.findDue();
            } catch (RuntimeException ex) {
                log.warn("Could not load due git sources: {}", ex.getMessage());
                return;
            }
            if (due.isEmpty()) {
                return;
            }
            log.info("Synchronizing {} due git source(s)", due.size());
            for (GitSourceRecord source : due) {
                try {
                    executor.sync(source.id(), GitSyncExecutor.TRIGGER_SCHEDULED);
                } catch (RuntimeException ex) {
                    // A single broken source must not stop the others; the executor
                    // already records failures, so this is a last-resort guard.
                    log.warn("Scheduled sync of git source {} failed: {}", source.id(), ex.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
