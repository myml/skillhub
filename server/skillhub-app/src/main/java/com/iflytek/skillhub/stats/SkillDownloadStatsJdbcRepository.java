package com.iflytek.skillhub.stats;

import com.iflytek.skillhub.domain.stats.SkillDownloadDailyPoint;
import com.iflytek.skillhub.domain.stats.SkillDownloadDimensionCount;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import com.iflytek.skillhub.domain.stats.SkillDownloadTopSkill;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed download statistics store.
 *
 * <p>Writes stay deliberately small (one append per download) while reads run
 * against the day-granularity rollup table, which a scheduled task recomputes.
 */
@Repository
public class SkillDownloadStatsJdbcRepository implements SkillDownloadStatsRepository {

    private final JdbcTemplate jdbcTemplate;

    public SkillDownloadStatsJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void recordEvent(Long skillId,
                            Long versionId,
                            String channel,
                            String actorId,
                            String clientHash,
                            Instant occurredAt) {
        jdbcTemplate.update(
                """
                INSERT INTO skill_download_event
                    (skill_id, version_id, channel, actor_id, client_hash, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                skillId,
                versionId,
                channel,
                actorId,
                clientHash,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC)
        );
    }

    @Override
    public int rollupDaily(Instant from, Instant to) {
        return jdbcTemplate.update(
                """
                INSERT INTO skill_download_daily
                    (skill_id, version_id, channel, day, download_count, updated_at)
                SELECT skill_id,
                       COALESCE(version_id, 0),
                       COALESCE(channel, 'unknown'),
                       (occurred_at AT TIME ZONE 'UTC')::date,
                       COUNT(*),
                       CURRENT_TIMESTAMP
                FROM skill_download_event
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY 1, 2, 3, 4
                ON CONFLICT (skill_id, version_id, channel, day)
                DO UPDATE SET download_count = EXCLUDED.download_count,
                              updated_at = CURRENT_TIMESTAMP
                """,
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC)
        );
    }

    @Override
    public int deleteEventsBefore(Instant threshold) {
        return jdbcTemplate.update(
                "DELETE FROM skill_download_event WHERE occurred_at < ?",
                OffsetDateTime.ofInstant(threshold, ZoneOffset.UTC)
        );
    }

    @Override
    public long countDownloads(Long skillId, LocalDate from, LocalDate to) {
        Long total = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(download_count), 0)
                FROM skill_download_daily
                WHERE skill_id = ? AND day BETWEEN ? AND ?
                """,
                Long.class,
                skillId,
                Date.valueOf(from),
                Date.valueOf(to)
        );
        return total == null ? 0L : total;
    }

    @Override
    public List<SkillDownloadDailyPoint> findDailySeries(Long skillId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                """
                SELECT day, SUM(download_count) AS total
                FROM skill_download_daily
                WHERE skill_id = ? AND day BETWEEN ? AND ?
                GROUP BY day
                ORDER BY day
                """,
                (rs, rowNum) -> new SkillDownloadDailyPoint(rs.getDate("day").toLocalDate(), rs.getLong("total")),
                skillId,
                Date.valueOf(from),
                Date.valueOf(to)
        );
    }

    @Override
    public List<SkillDownloadDimensionCount> countByChannel(Long skillId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                """
                SELECT channel AS dimension, SUM(download_count) AS total
                FROM skill_download_daily
                WHERE skill_id = ? AND day BETWEEN ? AND ?
                GROUP BY channel
                ORDER BY total DESC, channel ASC
                """,
                (rs, rowNum) -> new SkillDownloadDimensionCount(rs.getString("dimension"), rs.getLong("total")),
                skillId,
                Date.valueOf(from),
                Date.valueOf(to)
        );
    }

    @Override
    public List<SkillDownloadDimensionCount> countByVersion(Long skillId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                """
                SELECT CAST(version_id AS VARCHAR) AS dimension, SUM(download_count) AS total
                FROM skill_download_daily
                WHERE skill_id = ? AND day BETWEEN ? AND ?
                GROUP BY version_id
                ORDER BY total DESC, version_id ASC
                """,
                (rs, rowNum) -> new SkillDownloadDimensionCount(rs.getString("dimension"), rs.getLong("total")),
                skillId,
                Date.valueOf(from),
                Date.valueOf(to)
        );
    }

    @Override
    public List<SkillDownloadTopSkill> findTopPublicSkills(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query(
                """
                SELECT s.id AS skill_id,
                       n.slug AS namespace_slug,
                       s.slug AS skill_slug,
                       s.display_name AS display_name,
                       SUM(d.download_count) AS total
                FROM skill_download_daily d
                JOIN skill s ON s.id = d.skill_id
                JOIN namespace n ON n.id = s.namespace_id
                WHERE d.day BETWEEN ? AND ?
                  AND s.status = 'ACTIVE'
                  AND s.hidden = FALSE
                  AND s.visibility = 'PUBLIC'
                GROUP BY s.id, n.slug, s.slug, s.display_name
                ORDER BY total DESC, s.id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new SkillDownloadTopSkill(
                        rs.getLong("skill_id"),
                        rs.getString("namespace_slug"),
                        rs.getString("skill_slug"),
                        rs.getString("display_name"),
                        rs.getLong("total")
                ),
                Date.valueOf(from),
                Date.valueOf(to),
                limit
        );
    }
}
