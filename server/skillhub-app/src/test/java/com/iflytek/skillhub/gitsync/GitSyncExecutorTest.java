package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.gitsync.git.GitCommitRef;
import com.iflytek.skillhub.gitsync.git.GitCredential;
import com.iflytek.skillhub.gitsync.git.GitMirrorStore;
import com.iflytek.skillhub.gitsync.git.GitRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitProviderException;
import com.iflytek.skillhub.gitsync.git.GitRepositoryRef;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
import com.iflytek.skillhub.gitsync.scan.DiscoveredSkill;
import com.iflytek.skillhub.gitsync.scan.GitSourceScan;
import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.GitSyncRunRecord;
import com.iflytek.skillhub.gitsync.store.SkillSourceLinkRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitSyncExecutorTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final String COMMIT = "c".repeat(40);
    private static final String SKILL_DOCUMENT = """
            ---
            name: PDF Toolkit
            description: Extracts text from PDF files.
            version: 1.0.0
            ---

            # PDF Toolkit
            """;

    @Mock
    private GitSourceStore store;

    @Mock
    private GitSourcePlanService planService;

    @Mock
    private GitRepositoryFetcher fetcher;

    @Mock
    private SkillPublishService publishService;

    @Mock
    private NamespaceRepository namespaceRepository;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillVersionRepository skillVersionRepository;

    @Mock
    private GitMirrorStore mirrors;

    private GitSyncExecutor executor;

    private final GitSyncProperties properties = new GitSyncProperties();

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        // Without this, a request for 'en' falls back to the JVM's default locale
        // (often zh_CN) instead of the base messages.properties bundle.
        messages.setFallbackToSystemLocale(false);
        executor = new GitSyncExecutor(store, planService, fetcher,
                publishService, namespaceRepository, skillRepository, skillVersionRepository,
                new SkillMetadataParser(), mirrors, properties, messages, Clock.fixed(NOW, ZoneOffset.UTC));

        // Quotas are covered separately; these runs must not trip the mirror check.
        lenient().when(mirrors.size(any())).thenReturn(0L);
        lenient().when(store.insertRun(any())).thenReturn(77L);
        lenient().when(store.findById(5L)).thenReturn(Optional.of(source()));
        lenient().when(planService.repository(any())).thenReturn(
                new GitRepositoryRef(5L, "https://github.com/owner/repo", "owner/repo",
                        GitCredential.token(null, "token")));
        lenient().when(namespaceRepository.findBySlug("global"))
                .thenReturn(Optional.of(new Namespace("global", "Global", "admin-1")));
    }

    @Test
    void failsTheRunAndDropsTheMirrorWhenItOutgrewTheQuota() {
        properties.getLimits().setMaxMirrorBytes(1024L);
        givenScan(skill("pdf-toolkit", blob("pdf-toolkit/SKILL.md", "sha-1")));
        given(mirrors.size(5L)).willReturn(4096L);

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("FAILED", run.status());
        assertTrue(run.message().contains("discarded"), run.message());
        // The quota is a disk guard: the oversized mirror does not stay behind.
        then(mirrors).should().delete(5L);
        then(publishService).shouldHaveNoInteractions();
    }

    @Test
    void importsWhileTheMirrorStaysUnderTheQuota() {
        properties.getLimits().setMaxMirrorBytes(1024L);
        givenScan(skill("pdf-toolkit", blob("pdf-toolkit/SKILL.md", "sha-1")));
        givenPlan(item("pdf-toolkit", GitImportPlanItem.ChangeType.ADDED, null));
        given(fetcher.readBlob(any(), eq("sha-1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(mirrors.size(5L)).willReturn(512L);
        given(publishService.publishFromEntries(any(), any(), any(), any(), any()))
                .willReturn(new SkillPublishService.PublishResult(42L, "pdf-toolkit", null));

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status(), run.message());
        then(mirrors).should(never()).delete(5L);
    }

    private static GitSourceRecord source() {
        return new GitSourceRecord(5L, "https://github.com/owner/repo", "owner/repo",
                "main", "", "global", "github-token", "admin-1", 60, true,
                SkillVisibility.PUBLIC, "admin-1", NOW, NOW, null, null, null, null);
    }

    private static GitTreeEntry blob(String path, String sha) {
        return new GitTreeEntry(path, "blob", sha, SKILL_DOCUMENT.length());
    }

    private void givenScan(DiscoveredSkill... skills) {
        given(planService.scan(any())).willReturn(new GitSourceScan(5L, "main", COMMIT, "",
                List.of(skills), Map.of()));
    }

    private void givenPlan(GitImportPlanItem... items) {
        given(planService.plan(any(), any())).willReturn(
                new GitImportPlan(5L, "main", COMMIT, "", List.of(items), NOW));
    }

    private static GitImportPlanItem item(String itemPath, GitImportPlanItem.ChangeType changeType, Long skillId) {
        return new GitImportPlanItem(itemPath, "PDF Toolkit", "tree-" + itemPath, changeType, null, skillId, 2,
                List.of(), List.of());
    }

    private static DiscoveredSkill skill(String itemPath, GitTreeEntry... files) {
        return new DiscoveredSkill(itemPath, "tree-" + itemPath, List.of(files));
    }

    private GitSyncRunRecord captureFinishedRun() {
        ArgumentCaptor<GitSyncRunRecord> captor = ArgumentCaptor.forClass(GitSyncRunRecord.class);
        then(store).should().finishRun(captor.capture());
        return captor.getValue();
    }

    private List<PackageEntry> capturePublishedEntries() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PackageEntry>> captor = ArgumentCaptor.forClass(List.class);
        then(publishService).should().publishFromEntries(eq("global"), captor.capture(), eq("admin-1"),
                eq(SkillVisibility.PUBLIC), eq(java.util.Set.of()));
        return captor.getValue();
    }

    private void givenPublishSucceeds() {
        given(publishService.publishFromEntries(any(), any(), any(), any(), any()))
                .willReturn(new SkillPublishService.PublishResult(42L, "pdf-toolkit", null));
    }

    @Test
    void importsAddedDirectoryAndRecordsProvenance() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1"), blob("skills/pdf/run.py", "s2")));
        givenPlan(item("skills/pdf", GitImportPlanItem.ChangeType.ADDED, null));
        given(fetcher.readBlob(any(), eq("s1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(fetcher.readBlob(any(), eq("s2"))).willReturn("print('hi')".getBytes(StandardCharsets.UTF_8));
        givenPublishSucceeds();

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status());
        assertEquals(1, run.addedCount());
        assertEquals(0, run.failedCount());

        List<PackageEntry> entries = capturePublishedEntries();
        assertEquals(List.of("SKILL.md", "run.py"), entries.stream().map(PackageEntry::path).toList());
        assertEquals("text/markdown", entries.getFirst().contentType());

        ArgumentCaptor<GitSourceItemRecord> itemCaptor = ArgumentCaptor.forClass(GitSourceItemRecord.class);
        then(store).should().upsertItem(itemCaptor.capture());
        assertEquals(GitSourceItemRecord.STATE_IMPORTED, itemCaptor.getValue().state());
        assertEquals("tree-skills/pdf", itemCaptor.getValue().treeSha());
        assertEquals(42L, itemCaptor.getValue().skillId());

        ArgumentCaptor<SkillSourceLinkRecord> linkCaptor = ArgumentCaptor.forClass(SkillSourceLinkRecord.class);
        then(store).should().upsertLink(linkCaptor.capture());
        assertEquals("skills/pdf", linkCaptor.getValue().itemPath());
        assertEquals(COMMIT, linkCaptor.getValue().commitSha());
        assertEquals(42L, linkCaptor.getValue().skillId());
        then(store).should().markSourceSynced(5L, "SUCCESS", null);
    }

    @Test
    void skipsFilesTheRegistryRejectsAndStillImportsTheRest() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1"), blob("skills/pdf/LICENSE", "s2")));
        givenPlan(item("skills/pdf", GitImportPlanItem.ChangeType.ADDED, null));
        given(fetcher.readBlob(any(), eq("s1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        givenPublishSucceeds();

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status());
        assertEquals(List.of("SKILL.md"), capturePublishedEntries().stream().map(PackageEntry::path).toList());
        then(fetcher).should(never()).readBlob(any(), eq("s2"));
        assertNotNull(run.message());
        assertTrue(run.message().contains("skipped 1 file"));
    }

    @Test
    void keepsGoingWhenOneDirectoryFails() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1")),
                skill("skills/broken", blob("skills/broken/SKILL.md", "s2")));
        givenPlan(item("skills/broken", GitImportPlanItem.ChangeType.ADDED, null),
                item("skills/pdf", GitImportPlanItem.ChangeType.ADDED, null));
        given(fetcher.readBlob(any(), eq("s1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(fetcher.readBlob(any(), eq("s2"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(publishService.publishFromEntries(any(), any(), any(), any(), any()))
                .willThrow(new DomainBadRequestException("error.skill.publish.publisher.notMember", "global"))
                .willReturn(new SkillPublishService.PublishResult(42L, "pdf-toolkit", null));

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("PARTIAL", run.status());
        assertEquals(1, run.addedCount());
        assertEquals(1, run.failedCount());
        assertTrue(run.message().contains("skills/broken"));
        // Resolved text, not the raw code.
        assertTrue(run.message().contains("Publisher is not a member of namespace: global"));

        ArgumentCaptor<GitSourceItemRecord> items = ArgumentCaptor.forClass(GitSourceItemRecord.class);
        then(store).should(org.mockito.Mockito.times(2)).upsertItem(items.capture());
        assertEquals(List.of(GitSourceItemRecord.STATE_ERROR, GitSourceItemRecord.STATE_IMPORTED),
                items.getAllValues().stream().map(GitSourceItemRecord::state).toList());
    }

    @Test
    void leavesUnchangedDirectoriesUntouched() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1")));
        givenPlan(item("skills/pdf", GitImportPlanItem.ChangeType.UNCHANGED, 42L));

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status());
        assertEquals(1, run.unchangedCount());
        then(fetcher).should(never()).readBlob(any(), any());
        then(publishService).shouldHaveNoInteractions();
        ArgumentCaptor<GitSourceItemRecord> itemCaptor = ArgumentCaptor.forClass(GitSourceItemRecord.class);
        then(store).should().upsertItem(itemCaptor.capture());
        assertEquals(GitSourceItemRecord.STATE_IMPORTED, itemCaptor.getValue().state());
        assertEquals(42L, itemCaptor.getValue().skillId());
    }

    @Test
    void marksRemovedDirectoriesWithoutDeletingPublishedContent() {
        givenScan();
        givenPlan(new GitImportPlanItem("skills/gone", "Gone", "tree-gone",
                GitImportPlanItem.ChangeType.REMOVED, "tree-gone", 43L, 0, List.of(), List.of()));

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status());
        assertEquals(1, run.removedCount());
        then(store).should().markItemRemoved(5L, "skills/gone");
        then(publishService).shouldHaveNoInteractions();
        assertTrue(run.message().contains("published content is kept"));
    }

    @Test
    void derivesNewVersionWhenDeclaredVersionIsAlreadyPublished() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1")));
        givenPlan(item("skills/pdf", GitImportPlanItem.ChangeType.CHANGED, 42L));
        given(fetcher.readBlob(any(), eq("s1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        Skill existing = new Skill(1L, "pdf-toolkit", "admin-1", SkillVisibility.PUBLIC);
        given(skillRepository.findByNamespaceIdAndSlugAndOwnerId(any(), eq("pdf-toolkit"), eq("admin-1")))
                .willReturn(Optional.of(existing));
        SkillVersion published = new SkillVersion(42L, "1.0.0", "admin-1");
        published.setStatus(SkillVersionStatus.PUBLISHED);
        given(skillVersionRepository.findBySkillIdAndVersion(any(), eq("1.0.0"))).willReturn(Optional.of(published));
        givenPublishSucceeds();

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("SUCCESS", run.status());
        String publishedDocument = new String(capturePublishedEntries().getFirst().content(), StandardCharsets.UTF_8);
        assertTrue(publishedDocument.contains("version: 1.0.0-cccccccc"), publishedDocument);
        assertTrue(run.message().contains("imported as 1.0.0-cccccccc"));
    }

    @Test
    void keepsDeclaredVersionWhenItIsNotPublishedYet() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1")));
        givenPlan(item("skills/pdf", GitImportPlanItem.ChangeType.ADDED, null));
        given(fetcher.readBlob(any(), eq("s1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(skillRepository.findByNamespaceIdAndSlugAndOwnerId(any(), any(), any())).willReturn(Optional.empty());
        givenPublishSucceeds();

        executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        String publishedDocument = new String(capturePublishedEntries().getFirst().content(), StandardCharsets.UTF_8);
        assertTrue(publishedDocument.contains("version: 1.0.0\n"));
    }

    @Test
    void publishNeverAsksForPrivilegedRoles() {
        givenScan(skill("skills/pdf", blob("skills/pdf/SKILL.md", "s1")));
        givenPlan(item("skills/pdf", GitImportPlanItem.ChangeType.ADDED, null));
        given(fetcher.readBlob(any(), eq("s1"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        givenPublishSucceeds();

        executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        // No platform role: imported versions go to review like any other publish.
        then(publishService).should().publishFromEntries(eq("global"), any(), eq("admin-1"),
                eq(SkillVisibility.PUBLIC), eq(java.util.Set.of()));
    }

    @Test
    void staysPartialWhenOnlyAUnchangedDirectoryKeepsFailing() {
        // Live scenario: 18 directories are already imported and unchanged, one
        // directory keeps failing the pre-publish check. The run is partial, the
        // source is in sync, and no version is created again.
        givenScan(skill("skills/ok", blob("skills/ok/SKILL.md", "s1")),
                skill("skills/broken", blob("skills/broken/SKILL.md", "s2")));
        givenPlan(item("skills/ok", GitImportPlanItem.ChangeType.UNCHANGED, 7L),
                item("skills/broken", GitImportPlanItem.ChangeType.CHANGED, null));
        given(fetcher.readBlob(any(), eq("s2"))).willReturn(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8));
        given(publishService.publishFromEntries(any(), any(), any(), any(), any()))
                .willThrow(new DomainBadRequestException("error.skill.publish.precheck.failed", "skills/broken"));

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("PARTIAL", run.status());
        assertEquals(0, run.addedCount());
        assertEquals(0, run.changedCount());
        assertEquals(1, run.unchangedCount());
        assertEquals(1, run.failedCount());
        // The domain exception carries a message code; the history must show the
        // resolved text, never the bare code.
        assertFalse(run.message().contains("error.skill.publish.precheck.failed"));
        assertTrue(run.message().contains("Pre-publish validation failed: skills/broken"));
    }

    @Test
    void failedScanMarksTheRunFailed() {
        given(planService.scan(any())).willThrow(new GitProviderException("GitHub did not return a commit for ref main"));

        GitSyncRunRecord run = executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        assertEquals("FAILED", run.status());
        assertEquals(0, run.addedCount());
        assertTrue(run.message().contains("did not return a commit"));
        then(publishService).shouldHaveNoInteractions();
        then(store).should().markSourceSynced(5L, "FAILED", run.message());
    }

    @Test
    void refusesToRunTwoSyncsOfTheSameSourceAtOnce() throws Exception {
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        given(planService.scan(any())).willAnswer(invocation -> {
            scanStarted.countDown();
            releaseScan.await(15, TimeUnit.SECONDS);
            return new GitSourceScan(5L, "main", COMMIT, "", List.of(), Map.of());
        });
        givenPlan();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<GitSyncRunRecord> inFlight = pool.submit(() -> executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL));
            assertTrue(scanStarted.await(10, TimeUnit.SECONDS), "the first sync should start scanning");

            GitSyncRunRecord second = executor.sync(5L, GitSyncExecutor.TRIGGER_SCHEDULED);

            assertEquals(GitSyncExecutor.STATUS_SKIPPED, second.status());
            assertTrue(second.message().contains("already running"));
            // The refused run is recorded, but it never touched the repository.
            then(publishService).should(never()).publishFromEntries(any(), any(), any(), any(), any());

            releaseScan.countDown();
            assertEquals("SUCCESS", inFlight.get(15, TimeUnit.SECONDS).status());
        } finally {
            releaseScan.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void closesRunsLeftRunningByACrashedProcess() {
        givenScan();
        givenPlan();

        executor.sync(5L, GitSyncExecutor.TRIGGER_MANUAL);

        then(store).should().failStaleRuns(eq(5L), anyString());
    }

    @Test
    void reportsUnknownSource() {
        given(store.findById(404L)).willReturn(Optional.empty());

        assertThrows(DomainNotFoundException.class, () -> executor.sync(404L, GitSyncExecutor.TRIGGER_MANUAL));
    }
}
