package com.iflytek.skillhub.gitsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.stats.SkillDownloadStatsRepository;
import com.iflytek.skillhub.gitsync.dto.GitImportPlanResponse;
import com.iflytek.skillhub.gitsync.dto.GitSourceCreateRequest;
import com.iflytek.skillhub.gitsync.git.GitCommitRef;
import com.iflytek.skillhub.gitsync.git.GitRepositoryRef;
import com.iflytek.skillhub.gitsync.git.GitCliRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitCommandRunner;
import com.iflytek.skillhub.gitsync.git.GitMirrorStore;
import com.iflytek.skillhub.gitsync.git.GitRefInspector;
import com.iflytek.skillhub.gitsync.git.GitRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.GitSyncRunRecord;
import com.iflytek.skillhub.gitsync.store.SkillSourceLinkRecord;
import com.iflytek.skillhub.search.SearchIndexService;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the git sync and download statistics features against a real
 * PostgreSQL server.
 *
 * <p>The default test profile runs on H2 with Flyway disabled, so the
 * PostgreSQL-only SQL in the migration files and in the JDBC repositories
 * (upserts, {@code RETURNING}, {@code INTERVAL}, {@code date_trunc}) is only
 * exercised here. The test is opt-in because it needs a server:
 *
 * <pre>
 * SKILLHUB_PG_TESTS=true SKILLHUB_PG_URL=jdbc:postgresql://127.0.0.1:55432/skillhub \
 *   ./mvnw -pl skillhub-app -am -Dtest=GitSyncPostgresIntegrationTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@SpringBootTest
@ActiveProfiles({"test", "pgtest"})
@Import(TestRedisConfig.class)
@EnabledIfEnvironmentVariable(named = "SKILLHUB_PG_TESTS", matches = "true")
class GitSyncPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final String COMMIT = "d".repeat(40);
    private static final String SKILL_DOCUMENT = """
            ---
            name: Postgres Toolkit
            description: Verifies the sync path on a real database.
            version: 1.0.0
            ---

            # Postgres Toolkit
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GitSourceStore store;

    @Autowired
    private NamespaceRepository namespaceRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private SkillVersionRepository skillVersionRepository;

    @Autowired
    private SkillPublishService publishService;

    @Autowired
    private SkillMetadataParser metadataParser;

    @Autowired
    private SkillDownloadStatsRepository statsRepository;

    @MockBean
    private NamespaceMemberRepository namespaceMemberRepository;

    @MockBean
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private SearchIndexService searchIndexService;

    /**
     * The executor reads repository content through git; this test replaces only
     * that boundary so the database work (sources, items, runs, review tasks,
     * stats) is what is actually exercised.
     */
    private GitRepositoryFetcher fakeFetcher() {
        GitRepositoryFetcher fetcher = org.mockito.Mockito.mock(GitRepositoryFetcher.class);
        given(fetcher.resolveRef(any(), any())).willReturn(new GitCommitRef(COMMIT, "main"));
        given(fetcher.listTree(any(), any())).willReturn(List.of(
                new GitTreeEntry("skills/pg/SKILL.md", "blob", "sha-md", SKILL_DOCUMENT.length())));
        given(fetcher.readBlob(any(), anyString()))
                .willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(fetcher.readFile(any(), any(), any())).willReturn(SKILL_DOCUMENT);
        return fetcher;
    }

    private GitSyncExecutor executor(GitRepositoryFetcher fetcher) {
        return executor(fetcher, mirrorStore());
    }

    private static GitSyncUrlPolicy urlPolicy() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.getUrl().setAllowedHosts(List.of("github.com", "example.com", "localhost"));
        return new GitSyncUrlPolicy(properties);
    }

    private GitSyncExecutor executor(GitRepositoryFetcher fetcher, GitMirrorStore mirrors) {
        GitSourcePlanService planService = new GitSourcePlanService(store, fetcher,
                new GitCredentialResolver(),
                metadataParser, urlPolicy(), precheck(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new GitSyncExecutor(store, planService, fetcher, publishService, namespaceRepository,
                skillRepository, skillVersionRepository, metadataParser, mirrorStore(), new GitSyncProperties(),
                messages(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        // Without this, a request for 'en' falls back to the JVM's default locale
        // (often zh_CN) instead of the base messages.properties bundle.
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    private GitPlanPrecheck precheck() {
        return new GitPlanPrecheck(namespaceRepository, namespaceMemberRepository,
                skillRepository, skillVersionRepository);
    }

    /** Mirrors are never touched here: the fetcher is replaced by a mock. */
    private GitMirrorStore mirrorStore() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.setWorkDir(Path.of(System.getProperty("java.io.tmpdir"), "skillhub-pg-test-mirrors"));
        return new GitMirrorStore(new GitCommandRunner(properties), properties);
    }

    /**
     * Namespaces, skills, and download events reference {@code user_account}, so
     * every actor used by this test needs a real row (H2 tests never see these
     * constraints because they use generated DDL).
     */
    private String createUser(String prefix) {
        String userId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO user_account (id, display_name, email, status) VALUES (?, ?, ?, 'ACTIVE')",
                userId, userId, userId + "@example.com");
        return userId;
    }

    private Namespace createNamespace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String creatorId = createUser("creator");
        return namespaceRepository.save(new Namespace("pg-" + suffix, "PG " + suffix, creatorId));
    }

    private GitSourceRecord newSource(String namespaceSlug, String publisherId) {
        GitSourceRecord source = new GitSourceRecord(null, "https://github.com/owner/repo",
                "owner/repo", "main", "skills", namespaceSlug, null, publisherId, 60, true,
                SkillVisibility.PUBLIC, publisherId, NOW, NOW, null, null, null, null);
        Long id = store.insert(source);
        return store.findById(id).orElseThrow();
    }


    @Test
    @Transactional
    void planExplainsWhyADirectoryWouldFailWhenTheNameIsAlreadyPublished() {
        Namespace namespace = createNamespace();
        String publisherId = createUser("publisher");
        String rivalId = createUser("rival");
        given(namespaceMemberRepository.findByNamespaceIdAndUserId(anyLong(), anyString()))
                .willReturn(Optional.of(new NamespaceMember(namespace.getId(), publisherId, null)));

        // Another owner already published a skill with the name this repository would import.
        Skill rival = skillRepository.save(
                new Skill(namespace.getId(), "postgres-toolkit", rivalId, SkillVisibility.PUBLIC));
        SkillVersion rivalVersion = new SkillVersion(rival.getId(), "0.1.0", rivalId);
        rivalVersion.setStatus(SkillVersionStatus.PUBLISHED);
        rivalVersion.setPublishedAt(Instant.now());
        skillVersionRepository.save(rivalVersion);

        GitSourceRecord source = newSource(namespace.getSlug(), publisherId);
        GitRepositoryFetcher fetcher = fakeFetcher();
        GitSyncExecutor executor = executor(fetcher);
        GitSourcePlanService planService = new GitSourcePlanService(store, fetcher,
                new GitCredentialResolver(),
                metadataParser, urlPolicy(), precheck(), Clock.fixed(NOW, ZoneOffset.UTC));

        GitImportPlanResponse plan = new GitSyncAdminService(store, namespaceRepository, planService,
                executor, mirrorStore(), new GitRefInspector(mirrorStore(), new GitSyncProperties()),
                new GitCredentialResolver(), urlPolicy(), new GitSyncProperties(), namespaceMemberRepository,
                auditLogService, objectMapper, messages(),
                Clock.fixed(NOW, ZoneOffset.UTC)).plan(source.id(), "admin-1", true);

        // A directory the pre-check rejects is a predicted failure, not an addition.
        assertEquals(0, plan.added());
        assertEquals(1, plan.failing());
        assertThat(plan.items().getFirst().failing()).isTrue();
        assertEquals(1, plan.items().getFirst().problems().size());
        assertThat(plan.items().getFirst().problems().getFirst()).contains("postgres-toolkit");

        // The dry-run must not touch the registry: the rival's skill is still the only one.
        assertThat(skillRepository.findByNamespaceIdAndSlug(namespace.getId(), "postgres-toolkit")).hasSize(1);
    }

    @Test
    void flywayCreatesTheGitSyncAndDownloadStatsSchema() {
        // V42 removed api_base_url (syncing goes through git, not a provider API),
        // V43/V44 removed the platform and the credential-reference model, and V45
        // removed auto_publish (every imported version goes to review).
        assertThat(columns("git_source")).doesNotContain("api_base_url", "provider", "auth_type", "credential_ref",
                "auto_publish");
        assertThat(columns("git_source")).contains("repo_url", "repo_path", "default_ref", "subpath",
                "namespace_slug", "credential", "publisher_id", "sync_interval_minutes",
                "enabled", "default_visibility", "last_sync_at", "last_sync_status");
        assertThat(columns("git_source_item")).contains("source_id", "item_path", "tree_sha", "commit_sha", "state",
                "skill_id", "last_seen_at", "last_synced_at");
        assertThat(columns("skill_source_link")).contains("skill_id", "source_id", "item_path", "ref", "commit_sha",
                "tree_sha", "synced_at");
        assertThat(columns("git_sync_run")).contains("source_id", "trigger_type", "status", "planned_count",
                "added_count", "changed_count", "unchanged_count", "removed_count", "failed_count", "message");
        assertThat(columns("skill_download_event")).contains("skill_id", "version_id", "channel", "actor_id",
                "client_hash", "occurred_at");
        assertThat(columns("skill_download_daily")).contains("skill_id", "version_id", "channel", "day",
                "download_count");

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isNotNull().isGreaterThanOrEqualTo(40);
    }

    @Test
    @Transactional
    void syncImportsSkillAndLeavesItInReview() {
        Namespace namespace = createNamespace();
        String publisherId = createUser("publisher");
        given(namespaceMemberRepository.findByNamespaceIdAndUserId(anyLong(), anyString()))
                .willReturn(Optional.of(new NamespaceMember(namespace.getId(), publisherId, null)));

        GitSourceRecord source = newSource(namespace.getSlug(), publisherId);
        GitRepositoryFetcher fetcher = fakeFetcher();
        GitSyncExecutor executor = executor(fetcher);

        // The source has never been synchronized, so the schedule query must pick it up.
        assertThat(store.findDue()).extracting(GitSourceRecord::id).contains(source.id());

        // Dry run first: it must not modify the registry and must report one addition.
        GitSourcePlanService planService = new GitSourcePlanService(store, fetcher,
                new GitCredentialResolver(),
                metadataParser, urlPolicy(), precheck(), Clock.fixed(NOW, ZoneOffset.UTC));
        GitImportPlanResponse plan = new GitSyncAdminService(store, namespaceRepository, planService,
                executor, mirrorStore(), new GitRefInspector(mirrorStore(), new GitSyncProperties()),
                new GitCredentialResolver(), urlPolicy(), new GitSyncProperties(), namespaceMemberRepository,
                auditLogService, objectMapper, messages(),
                Clock.fixed(NOW, ZoneOffset.UTC)).plan(source.id(), "admin-1", true);
        assertEquals(1, plan.added());
        assertThat(skillRepository.findByNamespaceIdAndSlug(namespace.getId(), "postgres-toolkit")).isEmpty();

        GitSyncRunRecord run = executor.sync(source.id(), GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status(), run.message());
        assertEquals(1, run.addedCount());
        assertEquals(0, run.failedCount());

        // The version must land in review: auto_publish is off for this source.
        Skill skill = skillRepository.findByNamespaceIdAndSlug(namespace.getId(), "postgres-toolkit")
                .stream().findFirst().orElseThrow();
        SkillVersion version = skillVersionRepository.findBySkillIdAndVersion(skill.getId(), "1.0.0").orElseThrow();
        assertEquals(SkillVersionStatus.PENDING_REVIEW, version.getStatus());

        // Provenance and item state are persisted through the PostgreSQL upserts.
        SkillSourceLinkRecord link = store.findLinkBySkillId(skill.getId()).orElseThrow();
        assertEquals(source.id(), link.sourceId());
        assertEquals("skills/pg", link.itemPath());
        assertEquals(COMMIT, link.commitSha());

        List<GitSourceItemRecord> items = store.findItems(source.id());
        assertEquals(1, items.size());
        assertEquals(GitSourceItemRecord.STATE_IMPORTED, items.getFirst().state());
        assertEquals(skill.getId(), items.getFirst().skillId());

        // Run history and source status are readable back through the JDBC store.
        List<GitSyncRunRecord> runs = store.findRuns(source.id(), 10);
        assertEquals(1, runs.size());
        assertThat(runs.getFirst().finishedAt()).isNotNull();
        GitSourceRecord reloaded = store.findById(source.id()).orElseThrow();
        assertEquals("SUCCESS", reloaded.lastSyncStatus());
        assertThat(reloaded.lastSyncAt()).isNotNull();

        // A second sync must be a no-op: the tree SHA is unchanged.
        assertEquals(0, store.findDue().stream().filter(due -> due.id().equals(source.id())).count());
        GitSyncRunRecord second = executor.sync(source.id(), GitSyncExecutor.TRIGGER_MANUAL);
        assertEquals(0, second.addedCount());
        assertEquals(1, second.unchangedCount());

        // The provenance endpoint reads through the same link row.
        assertEquals("skills/pg", store.findLinkBySkillId(skill.getId()).orElseThrow().itemPath());
        org.junit.jupiter.api.Assertions.assertThrows(DomainNotFoundException.class,
                () -> store.findLinkBySkillId(skill.getId() + 999L).orElseThrow(
                        () -> new DomainNotFoundException("error.gitSource.item.notFound", "missing")));

        // Deleting the skill must stay possible: the provenance rows cascade away
        // instead of blocking the upstream delete flow with a foreign key error.
        Long skillId = skill.getId();
        // A pending version has a review task, which is why the sync did go
        // through the normal review gate.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_task rt JOIN skill_version sv ON sv.id = rt.skill_version_id"
                        + " WHERE sv.skill_id = ?", Integer.class, skillId)).isGreaterThan(0);
        jdbcTemplate.update(
                "DELETE FROM review_task WHERE skill_version_id IN (SELECT id FROM skill_version WHERE skill_id = ?)",
                skillId);
        jdbcTemplate.update("DELETE FROM skill_file WHERE version_id IN (SELECT id FROM skill_version WHERE skill_id = ?)",
                skillId);
        skillVersionRepository.deleteBySkillId(skillId);
        skillRepository.delete(skill);
        skillRepository.flush();
        assertThat(store.findLinkBySkillId(skillId)).isEmpty();
    }

    @Test
    void downloadStatsRollupRunsOnPostgres() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        Namespace namespace = createNamespace();
        String ownerId = createUser("owner");
        Skill skill = skillRepository.save(new Skill(namespace.getId(), "stats-" + UUID.randomUUID(), ownerId,
                SkillVisibility.PUBLIC));
        Long skillId = skill.getId();
        // The database may already hold real download traffic; scope the test to
        // its own day so the assertions stay exact.
        jdbcTemplate.update("DELETE FROM skill_download_event WHERE occurred_at >= ? AND occurred_at < ?",
                java.sql.Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()),
                java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        statsRepository.recordEvent(skillId, 55L, "API", "user-1", "hash-1", day.atTime(9, 0).toInstant(ZoneOffset.UTC));
        statsRepository.recordEvent(skillId, 55L, "API", "user-2", "hash-2", day.atTime(10, 0).toInstant(ZoneOffset.UTC));
        statsRepository.recordEvent(skillId, 56L, "CLI", "user-1", "hash-1", day.atTime(11, 0).toInstant(ZoneOffset.UTC));

        int written = statsRepository.rollupDaily(
                day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(written).isGreaterThan(0);

        assertEquals(3, statsRepository.countDownloads(skillId, day, day));
        assertThat(statsRepository.countByChannel(skillId, day, day))
                .extracting(count -> count.key() + "=" + count.count())
                .containsExactlyInAnyOrder("API=2", "CLI=1");
        assertThat(statsRepository.countByVersion(skillId, day, day))
                .extracting(count -> count.key() + "=" + count.count())
                .containsExactlyInAnyOrder("55=2", "56=1");

        // Re-running the rollup replaces the day instead of double counting.
        statsRepository.rollupDaily(
                day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertEquals(3, statsRepository.countDownloads(skillId, day, day));

        assertThat(statsRepository.deleteEventsBefore(day.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()))
                .isGreaterThanOrEqualTo(3);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_download_event WHERE skill_id = ?", Integer.class, skillId));
        assertEquals(3, statsRepository.countDownloads(skillId, day, day));
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?", String.class, table);
    }
}
