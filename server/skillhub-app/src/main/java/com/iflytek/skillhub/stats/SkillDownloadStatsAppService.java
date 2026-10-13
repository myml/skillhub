package com.iflytek.skillhub.stats;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.stats.SkillDownloadDimensionCount;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import com.iflytek.skillhub.domain.stats.SkillDownloadTopSkill;
import com.iflytek.skillhub.dto.SkillDownloadStatsResponse;
import com.iflytek.skillhub.dto.SkillDownloadTopItemResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side service that exposes download statistics for a single skill and the
 * public ranking, while enforcing skill visibility rules.
 */
@Service
public class SkillDownloadStatsAppService {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 365;
    private static final int MAX_TOP_LIMIT = 100;
    private static final Set<String> ADMIN_ROLES = Set.of("SUPER_ADMIN", "SKILL_ADMIN", "AUDITOR");

    private final SkillRepository skillRepository;
    private final SkillDownloadStatsRepository statsRepository;
    private final Clock clock;

    public SkillDownloadStatsAppService(SkillRepository skillRepository,
                                        SkillDownloadStatsRepository statsRepository,
                                        Clock clock) {
        this.skillRepository = skillRepository;
        this.statsRepository = statsRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SkillDownloadStatsResponse loadSkillStats(Long skillId,
                                                     LocalDate from,
                                                     LocalDate to,
                                                     Map<Long, NamespaceRole> userNamespaceRoles,
                                                     Set<String> platformRoles) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new DomainNotFoundException("error.skill.notFound", skillId));
        assertVisible(skill, userNamespaceRoles, platformRoles);

        LocalDate[] range = normalizeRange(from, to);
        LocalDate rangeFrom = range[0];
        LocalDate rangeTo = range[1];

        return new SkillDownloadStatsResponse(
                skill.getId(),
                rangeFrom.toString(),
                rangeTo.toString(),
                statsRepository.countDownloads(skill.getId(), rangeFrom, rangeTo),
                statsRepository.findDailySeries(skill.getId(), rangeFrom, rangeTo).stream()
                        .map(point -> new SkillDownloadStatsResponse.DailyPoint(point.day().toString(), point.count()))
                        .toList(),
                toDimensionCounts(statsRepository.countByChannel(skill.getId(), rangeFrom, rangeTo)),
                toDimensionCounts(statsRepository.countByVersion(skill.getId(), rangeFrom, rangeTo))
        );
    }

    @Transactional(readOnly = true)
    public List<SkillDownloadTopItemResponse> topSkills(int days, int limit) {
        int normalizedDays = Math.clamp(days, 1, MAX_RANGE_DAYS);
        int normalizedLimit = Math.clamp(limit, 1, MAX_TOP_LIMIT);

        LocalDate today = today();
        LocalDate from = today.minusDays(normalizedDays - 1L);

        return statsRepository.findTopPublicSkills(from, today, normalizedLimit).stream()
                .map(SkillDownloadStatsAppService::toTopItem)
                .toList();
    }

    private static SkillDownloadTopItemResponse toTopItem(SkillDownloadTopSkill row) {
        return new SkillDownloadTopItemResponse(
                row.skillId(),
                row.namespaceSlug(),
                row.skillSlug(),
                row.displayName(),
                row.count()
        );
    }

    private static List<SkillDownloadStatsResponse.DimensionCount> toDimensionCounts(
            List<SkillDownloadDimensionCount> counts) {
        return counts.stream()
                .map(count -> new SkillDownloadStatsResponse.DimensionCount(count.key(), count.count()))
                .toList();
    }

    private void assertVisible(Skill skill, Map<Long, NamespaceRole> userNamespaceRoles, Set<String> platformRoles) {
        if (skill.getStatus() != SkillStatus.ACTIVE || skill.isHidden()) {
            throw new DomainNotFoundException("error.skill.notFound", skill.getId());
        }
        if (skill.getVisibility() == SkillVisibility.PUBLIC) {
            return;
        }
        boolean namespaceMember = userNamespaceRoles != null && userNamespaceRoles.containsKey(skill.getNamespaceId());
        boolean platformAdmin = platformRoles != null && !Collections.disjoint(platformRoles, ADMIN_ROLES);
        if (!namespaceMember && !platformAdmin) {
            throw new DomainForbiddenException("error.skill.access.denied", skill.getId());
        }
    }

    private LocalDate[] normalizeRange(LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        LocalDate floor = end.minusDays(MAX_RANGE_DAYS - 1L);
        if (start.isBefore(floor)) {
            start = floor;
        }
        return new LocalDate[]{start, end};
    }

    private LocalDate today() {
        return Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
