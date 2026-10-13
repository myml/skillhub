package com.iflytek.skillhub.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.stats.SkillDownloadDailyPoint;
import com.iflytek.skillhub.domain.stats.SkillDownloadDimensionCount;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import com.iflytek.skillhub.dto.SkillDownloadStatsResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SkillDownloadStatsAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    private static final long NAMESPACE_ID = 3L;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillDownloadStatsRepository statsRepository;

    private SkillDownloadStatsAppService service() {
        return new SkillDownloadStatsAppService(
                skillRepository,
                statsRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static Skill skill(long id, SkillVisibility visibility) {
        Skill skill = new Skill(NAMESPACE_ID, "demo-skill", "owner-1", visibility);
        ReflectionTestUtils.setField(skill, "id", id);
        return skill;
    }

    @Test
    void loadSkillStats_publicSkill_returnsSeriesDimensionsAndTotal() {
        Skill skill = skill(9L, SkillVisibility.PUBLIC);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));
        given(statsRepository.countDownloads(9L, TODAY.minusDays(2), TODAY)).willReturn(12L);
        given(statsRepository.findDailySeries(9L, TODAY.minusDays(2), TODAY)).willReturn(List.of(
                new SkillDownloadDailyPoint(TODAY.minusDays(2), 5L),
                new SkillDownloadDailyPoint(TODAY, 7L)
        ));
        given(statsRepository.countByChannel(9L, TODAY.minusDays(2), TODAY)).willReturn(List.of(
                new SkillDownloadDimensionCount("cli", 9L),
                new SkillDownloadDimensionCount("web", 3L)
        ));
        given(statsRepository.countByVersion(9L, TODAY.minusDays(2), TODAY)).willReturn(List.of(
                new SkillDownloadDimensionCount("7", 12L)
        ));

        SkillDownloadStatsResponse response = service().loadSkillStats(
                9L, TODAY.minusDays(2), TODAY, Map.of(), Set.of());

        assertEquals(9L, response.skillId());
        assertEquals("2026-10-07", response.from());
        assertEquals("2026-10-09", response.to());
        assertEquals(12L, response.total());
        assertEquals(2, response.daily().size());
        assertEquals("2026-10-09", response.daily().get(1).day());
        assertEquals("cli", response.channels().getFirst().key());
        assertEquals("7", response.versions().getFirst().key());
    }

    @Test
    void loadSkillStats_defaultsToLastThirtyDaysWhenRangeMissing() {
        Skill skill = skill(9L, SkillVisibility.PUBLIC);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));
        given(statsRepository.countDownloads(eq(9L), any(), any())).willReturn(0L);
        given(statsRepository.findDailySeries(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByChannel(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByVersion(eq(9L), any(), any())).willReturn(List.of());

        SkillDownloadStatsResponse response = service().loadSkillStats(9L, null, null, Map.of(), Set.of());

        assertEquals("2026-09-10", response.from());
        assertEquals("2026-10-09", response.to());
    }

    @Test
    void loadSkillStats_clampsRangeToMaximumWindow() {
        Skill skill = skill(9L, SkillVisibility.PUBLIC);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));
        given(statsRepository.countDownloads(eq(9L), any(), any())).willReturn(0L);
        given(statsRepository.findDailySeries(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByChannel(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByVersion(eq(9L), any(), any())).willReturn(List.of());

        SkillDownloadStatsResponse response = service().loadSkillStats(
                9L, LocalDate.parse("2000-01-01"), TODAY, Map.of(), Set.of());

        assertEquals("2025-10-10", response.from());
    }

    @Test
    void loadSkillStats_privateSkill_requiresNamespaceMembershipOrAdminRole() {
        Skill skill = skill(9L, SkillVisibility.NAMESPACE_ONLY);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));

        assertThrows(DomainForbiddenException.class,
                () -> service().loadSkillStats(9L, null, null, Map.of(), Set.of("USER")));
    }

    @Test
    void loadSkillStats_privateSkill_isVisibleToNamespaceMember() {
        Skill skill = skill(9L, SkillVisibility.NAMESPACE_ONLY);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));
        given(statsRepository.countDownloads(eq(9L), any(), any())).willReturn(4L);
        given(statsRepository.findDailySeries(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByChannel(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByVersion(eq(9L), any(), any())).willReturn(List.of());

        SkillDownloadStatsResponse response = service().loadSkillStats(
                9L, null, null, Map.of(NAMESPACE_ID, NamespaceRole.MEMBER), Set.of());

        assertEquals(4L, response.total());
    }

    @Test
    void loadSkillStats_privateSkill_isVisibleToPlatformAdmin() {
        Skill skill = skill(9L, SkillVisibility.PRIVATE);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));
        given(statsRepository.countDownloads(eq(9L), any(), any())).willReturn(1L);
        given(statsRepository.findDailySeries(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByChannel(eq(9L), any(), any())).willReturn(List.of());
        given(statsRepository.countByVersion(eq(9L), any(), any())).willReturn(List.of());

        SkillDownloadStatsResponse response = service().loadSkillStats(9L, null, null, Map.of(), Set.of("SUPER_ADMIN"));

        assertEquals(1L, response.total());
    }

    @Test
    void loadSkillStats_hiddenSkill_isReportedAsMissing() {
        Skill skill = skill(9L, SkillVisibility.PUBLIC);
        ReflectionTestUtils.setField(skill, "status", SkillStatus.HIDDEN);
        given(skillRepository.findById(9L)).willReturn(Optional.of(skill));

        assertThrows(DomainNotFoundException.class,
                () -> service().loadSkillStats(9L, null, null, Map.of(), Set.of()));
    }

    @Test
    void loadSkillStats_unknownSkill_isReportedAsMissing() {
        given(skillRepository.findById(404L)).willReturn(Optional.empty());

        assertThrows(DomainNotFoundException.class,
                () -> service().loadSkillStats(404L, null, null, Map.of(), Set.of()));
    }

    @Test
    void topSkills_clampsDaysAndLimit() {
        given(statsRepository.findTopPublicSkills(any(), any(), anyInt())).willReturn(List.of());

        service().topSkills(0, 999);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        then(statsRepository).should().findTopPublicSkills(from.capture(), to.capture(), limit.capture());

        assertEquals(TODAY, from.getValue());
        assertEquals(TODAY, to.getValue());
        assertEquals(100, limit.getValue());
        assertTrue(from.getValue().isBefore(to.getValue().plusDays(1)));
    }
}
