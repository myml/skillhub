package com.iflytek.skillhub.gitsync.store;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC persistence for git sources, discovered items, provenance, and sync runs.
 *
 * <p>Uses PostgreSQL features ({@code RETURNING}, {@code ON CONFLICT}) like the
 * rest of the fork's new tables; the repository tests avoid these statements
 * because the test profile runs on H2.
 */
@Repository
public class GitSourceStore {

    private static final String SOURCE_COLUMNS = """
            id, repo_url, repo_path, default_ref, subpath, namespace_slug,
            credential, publisher_id, sync_interval_minutes, enabled,
            default_visibility, created_by, created_at, updated_at, last_planned_at, last_sync_at,
            last_sync_status, last_sync_message
            """;

    private static final String ITEM_COLUMNS = """
            id, source_id, item_path, skill_name, tree_sha, commit_sha, skill_id, state,
            last_seen_at, last_synced_at
            """;

    private static final RowMapper<GitSourceRecord> SOURCE_MAPPER = (rs, rowNum) -> new GitSourceRecord(
            rs.getLong("id"),
            rs.getString("repo_url"),
            rs.getString("repo_path"),
            rs.getString("default_ref"),
            rs.getString("subpath"),
            rs.getString("namespace_slug"),
            rs.getString("credential"),
            rs.getString("publisher_id"),
            rs.getInt("sync_interval_minutes"),
            rs.getBoolean("enabled"),
            SkillVisibility.valueOf(rs.getString("default_visibility")),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "last_planned_at"),
            instant(rs, "last_sync_at"),
            rs.getString("last_sync_status"),
            rs.getString("last_sync_message")
    );

    private static final RowMapper<GitSourceItemRecord> ITEM_MAPPER = (rs, rowNum) -> new GitSourceItemRecord(
            rs.getLong("id"),
            rs.getLong("source_id"),
            rs.getString("item_path"),
            rs.getString("skill_name"),
            rs.getString("tree_sha"),
            rs.getString("commit_sha"),
            rs.getObject("skill_id", Long.class),
            rs.getString("state"),
            instant(rs, "last_seen_at"),
            instant(rs, "last_synced_at")
    );

    private static final RowMapper<SkillSourceLinkRecord> LINK_MAPPER = (rs, rowNum) -> new SkillSourceLinkRecord(
            rs.getLong("id"),
            rs.getLong("skill_id"),
            rs.getObject("source_id", Long.class),
            rs.getString("repo_url"),
            rs.getString("item_path"),
            rs.getString("ref"),
            rs.getString("commit_sha"),
            rs.getString("tree_sha"),
            instant(rs, "synced_at")
    );

    private static final RowMapper<GitSyncRunRecord> RUN_MAPPER = (rs, rowNum) -> new GitSyncRunRecord(
            rs.getLong("id"),
            rs.getLong("source_id"),
            rs.getString("trigger_type"),
            rs.getString("status"),
            rs.getInt("planned_count"),
            rs.getInt("added_count"),
            rs.getInt("changed_count"),
            rs.getInt("unchanged_count"),
            rs.getInt("removed_count"),
            rs.getInt("failed_count"),
            rs.getString("message"),
            instant(rs, "started_at"),
            instant(rs, "finished_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public GitSourceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(GitSourceRecord source) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO git_source
                    (repo_url, repo_path, default_ref, subpath, namespace_slug,
                     credential, publisher_id, sync_interval_minutes, enabled,
                     default_visibility, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                source.repoUrl(),
                source.repoPath(),
                source.defaultRef(),
                source.subpath(),
                source.namespaceSlug(),
                source.credential(),
                source.publisherId(),
                source.syncIntervalMinutes(),
                source.enabled(),
                source.defaultVisibility().name(),
                source.createdBy()
        );
        if (id == null) {
            throw new IllegalStateException("Inserting git source did not return an id");
        }
        return id;
    }

    public void update(GitSourceRecord source) {
        jdbcTemplate.update(
                """
                UPDATE git_source
                SET repo_url = ?, repo_path = ?, default_ref = ?,
                    subpath = ?, namespace_slug = ?, credential = ?, publisher_id = ?,
                    sync_interval_minutes = ?, enabled = ?, default_visibility = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                source.repoUrl(),
                source.repoPath(),
                source.defaultRef(),
                source.subpath(),
                source.namespaceSlug(),
                source.credential(),
                source.publisherId(),
                source.syncIntervalMinutes(),
                source.enabled(),
                source.defaultVisibility().name(),
                source.id()
        );
    }

    public void delete(Long id) {
        jdbcTemplate.update("DELETE FROM git_source WHERE id = ?", id);
    }

    public Optional<GitSourceRecord> findById(Long id) {
        return jdbcTemplate.query("SELECT " + SOURCE_COLUMNS + " FROM git_source WHERE id = ?", SOURCE_MAPPER, id)
                .stream()
                .findFirst();
    }

    /**
     * The source registered for this exact location, i.e. the row the unique key
     * {@code (repo_url, subpath, namespace_slug)} guards.
     */
    public Optional<GitSourceRecord> findByLocation(String repoUrl, String subpath, String namespaceSlug) {
        return jdbcTemplate.query(
                        "SELECT " + SOURCE_COLUMNS + " FROM git_source"
                                + " WHERE repo_url = ? AND subpath = ? AND namespace_slug = ?",
                        SOURCE_MAPPER, repoUrl, subpath, namespaceSlug)
                .stream()
                .findFirst();
    }

    /** Sources a user registered, for the per-user quota. */
    public int countByCreatedBy(String createdBy) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM git_source WHERE created_by = ?", Integer.class, createdBy);
        return count == null ? 0 : count;
    }

    public List<GitSourceRecord> findAll() {
        return jdbcTemplate.query("SELECT " + SOURCE_COLUMNS + " FROM git_source ORDER BY id", SOURCE_MAPPER);
    }

    /**
     * Enabled sources whose sync interval has elapsed, oldest first.
     */
    public List<GitSourceRecord> findDue() {
        return jdbcTemplate.query(
                "SELECT " + SOURCE_COLUMNS
                        + " FROM git_source"
                        + " WHERE enabled = TRUE"
                        + " AND (last_sync_at IS NULL"
                        + " OR last_sync_at + (sync_interval_minutes * INTERVAL '1 minute') <= CURRENT_TIMESTAMP)"
                        + " ORDER BY last_sync_at NULLS FIRST, id",
                SOURCE_MAPPER
        );
    }

    public void touchLastPlanned(Long id) {
        jdbcTemplate.update("UPDATE git_source SET last_planned_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void markSourceSynced(Long id, String status, String message) {
        jdbcTemplate.update(
                """
                UPDATE git_source
                SET last_sync_at = CURRENT_TIMESTAMP, last_sync_status = ?, last_sync_message = ?
                WHERE id = ?
                """,
                status,
                truncate(message, 2048),
                id
        );
    }

    public List<GitSourceItemRecord> findItems(Long sourceId) {
        return jdbcTemplate.query(
                "SELECT " + ITEM_COLUMNS + " FROM git_source_item WHERE source_id = ? ORDER BY item_path",
                ITEM_MAPPER,
                sourceId
        );
    }

    /**
     * Records the observed state of a directory, creating the row on first sight.
     */
    public void upsertItem(GitSourceItemRecord item) {
        jdbcTemplate.update(
                """
                INSERT INTO git_source_item
                    (source_id, item_path, skill_name, tree_sha, commit_sha, skill_id, state,
                     last_seen_at, last_synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, item_path) DO UPDATE
                SET skill_name = EXCLUDED.skill_name,
                    tree_sha = EXCLUDED.tree_sha,
                    commit_sha = EXCLUDED.commit_sha,
                    skill_id = COALESCE(EXCLUDED.skill_id, git_source_item.skill_id),
                    state = EXCLUDED.state,
                    last_seen_at = EXCLUDED.last_seen_at,
                    last_synced_at = EXCLUDED.last_synced_at,
                    updated_at = CURRENT_TIMESTAMP
                """,
                item.sourceId(),
                item.itemPath(),
                truncate(item.skillName(), 128),
                item.treeSha(),
                item.commitSha(),
                item.skillId(),
                item.state(),
                toOffsetDateTime(item.lastSeenAt()),
                toOffsetDateTime(item.lastSyncedAt())
        );
    }

    public Optional<SkillSourceLinkRecord> findLinkBySkillId(Long skillId) {
        return jdbcTemplate.query(
                """
                SELECT id, skill_id, source_id, repo_url, item_path, ref, commit_sha, tree_sha, synced_at
                FROM skill_source_link
                WHERE skill_id = ?
                ORDER BY id DESC
                LIMIT 1
                """,
                LINK_MAPPER,
                skillId
        ).stream().findFirst();
    }

    /**
     * Records provenance for a skill, keeping one link per skill and source.
     */
    public void upsertLink(SkillSourceLinkRecord link) {
        jdbcTemplate.update(
                """
                INSERT INTO skill_source_link
                    (skill_id, source_id, repo_url, item_path, ref, commit_sha, tree_sha, synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (skill_id, source_id) DO UPDATE
                SET repo_url = EXCLUDED.repo_url,
                    item_path = EXCLUDED.item_path,
                    ref = EXCLUDED.ref,
                    commit_sha = EXCLUDED.commit_sha,
                    tree_sha = EXCLUDED.tree_sha,
                    synced_at = CURRENT_TIMESTAMP
                """,
                link.skillId(),
                link.sourceId(),
                link.repoUrl(),
                link.itemPath(),
                link.ref(),
                link.commitSha(),
                link.treeSha()
        );
    }

    /**
     * Number of skills imported per source, in one query so a source list does not
     * turn into one count per row.
     */
    public Map<Long, Integer> countImportedBySource() {
        Map<Long, Integer> counts = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT source_id, COUNT(*) AS imported
                FROM git_source_item
                WHERE state = ? AND skill_id IS NOT NULL
                GROUP BY source_id
                """,
                rs -> {
                    counts.put(rs.getLong("source_id"), rs.getInt("imported"));
                },
                GitSourceItemRecord.STATE_IMPORTED
        );
        return counts;
    }

    public Long insertRun(GitSyncRunRecord run) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO git_sync_run (source_id, trigger_type, status, message)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                run.sourceId(),
                run.triggerType(),
                run.status(),
                truncate(run.message(), 2048)
        );
        if (id == null) {
            throw new IllegalStateException("Inserting git sync run did not return an id");
        }
        return id;
    }

    public void finishRun(GitSyncRunRecord run) {
        jdbcTemplate.update(
                """
                UPDATE git_sync_run
                SET status = ?, planned_count = ?, added_count = ?, changed_count = ?, unchanged_count = ?,
                    removed_count = ?, failed_count = ?, message = ?, finished_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                run.status(),
                run.plannedCount(),
                run.addedCount(),
                run.changedCount(),
                run.unchangedCount(),
                run.removedCount(),
                run.failedCount(),
                truncate(run.message(), 2048),
                run.id()
        );
    }

    public List<GitSyncRunRecord> findRuns(Long sourceId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, source_id, trigger_type, status, planned_count, added_count, changed_count,
                       unchanged_count, removed_count, failed_count, message, started_at, finished_at
                FROM git_sync_run
                WHERE source_id = ?
                ORDER BY id DESC
                LIMIT ?
                """,
                RUN_MAPPER,
                sourceId,
                limit
        );
    }

    /**
     * Marks a directory as no longer present without touching the imported skill:
     * removing published content is a deliberate, separate operation.
     */
    public void markItemRemoved(Long sourceId, String itemPath) {
        jdbcTemplate.update(
                """
                UPDATE git_source_item
                SET state = 'REMOVED', last_seen_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE source_id = ? AND item_path = ?
                """,
                sourceId,
                itemPath
        );
    }

    /**
     * Closes run rows that are still marked {@code RUNNING} for a source.
     *
     * <p>A sync that dies with the JVM leaves its run row open; the next sync of
     * the same source closes it instead of showing a run that never finished.
     *
     * @return number of closed rows
     */
    public int failStaleRuns(Long sourceId, String message) {
        return jdbcTemplate.update(
                """
                UPDATE git_sync_run
                SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP, message = ?
                WHERE source_id = ? AND status = 'RUNNING'
                """,
                truncate(message, 1000),
                sourceId
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
