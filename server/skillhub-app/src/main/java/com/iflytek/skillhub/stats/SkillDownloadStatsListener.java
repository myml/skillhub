package com.iflytek.skillhub.stats;

import com.iflytek.skillhub.domain.event.SkillDownloadedEvent;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Persists a raw download event for every published {@link SkillDownloadedEvent}.
 *
 * <p>The producer publishes the event without an ambient transaction, so the
 * listener falls back to immediate execution and opens its own short
 * transaction. Failures are logged and swallowed: statistics must never break a
 * download response.
 */
@Component
public class SkillDownloadStatsListener {

    private static final Logger log = LoggerFactory.getLogger(SkillDownloadStatsListener.class);

    private final SkillDownloadStatsRepository repository;
    private final DownloadRequestContextResolver contextResolver;
    private final Clock clock;

    public SkillDownloadStatsListener(SkillDownloadStatsRepository repository,
                                      DownloadRequestContextResolver contextResolver,
                                      Clock clock) {
        this.repository = repository;
        this.contextResolver = contextResolver;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkillDownloaded(SkillDownloadedEvent event) {
        if (event == null || event.skillId() == null) {
            return;
        }
        try {
            DownloadRequestContext context = contextResolver.resolve();
            repository.recordEvent(
                    event.skillId(),
                    event.versionId(),
                    context.channel(),
                    context.actorId(),
                    context.clientHash(),
                    Instant.now(clock)
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to record download statistics for skill {}", event.skillId(), ex);
        }
    }
}
