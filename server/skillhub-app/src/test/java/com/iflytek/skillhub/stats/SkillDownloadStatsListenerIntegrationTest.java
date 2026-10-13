package com.iflytek.skillhub.stats;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.domain.event.SkillDownloadedEvent;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wiring test for the download statistics listener.
 *
 * <p>{@code SkillDownloadService} publishes the download event without an
 * ambient transaction, so this test asserts that the listener still records a
 * row in that exact scenario.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillDownloadStatsListenerIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockBean
    private SkillDownloadStatsRepository repository;

    @Test
    void publishedDownloadEvent_recordsStatisticsWithoutAmbientTransaction() {
        eventPublisher.publishEvent(new SkillDownloadedEvent(11L, 22L));

        verify(repository).recordEvent(eq(11L), eq(22L), any(), any(), any(), any());
    }
}
