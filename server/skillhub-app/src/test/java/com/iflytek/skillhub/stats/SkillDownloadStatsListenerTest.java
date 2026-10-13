package com.iflytek.skillhub.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

import com.iflytek.skillhub.domain.event.SkillDownloadedEvent;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillDownloadStatsListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @Mock
    private SkillDownloadStatsRepository repository;

    private SkillDownloadStatsListener listener() {
        return new SkillDownloadStatsListener(
                repository,
                new DownloadRequestContextResolver(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void onSkillDownloaded_recordsEventWithResolvedContextAndClock() {
        listener().onSkillDownloaded(new SkillDownloadedEvent(42L, 7L));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        then(repository).should().recordEvent(eq(42L), eq(7L), channel.capture(), any(), any(), eq(NOW));
        assertEquals(DownloadRequestContext.CHANNEL_UNKNOWN, channel.getValue());
        assertNotNull(channel.getValue());
    }

    @Test
    void onSkillDownloaded_swallowsRepositoryFailure() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).recordEvent(any(), any(), any(), any(), any(), any());

        listener().onSkillDownloaded(new SkillDownloadedEvent(42L, 7L));

        then(repository).should().recordEvent(eq(42L), eq(7L), any(), any(), any(), eq(NOW));
    }

    @Test
    void onSkillDownloaded_ignoresEventWithoutSkillId() {
        listener().onSkillDownloaded(new SkillDownloadedEvent(null, 7L));
        listener().onSkillDownloaded(null);

        then(repository).should(never()).recordEvent(any(), any(), any(), any(), any(), any());
    }
}
