package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.gitsync.dto.GitSourceCreateRequest;
import com.iflytek.skillhub.gitsync.dto.GitSourceResponse;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.gitsync.git.GitMirrorStore;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.PlanProblem;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
import com.iflytek.skillhub.gitsync.select.SkippedGitFile;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.gitsync.store.GitSyncRunRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

@ExtendWith(MockitoExtension.class)
class GitSyncAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @Mock
    private GitSourceStore store;

    @Mock
    private NamespaceRepository namespaceRepository;

    @Mock
    private GitSourcePlanService planService;

    @Mock
    private GitSyncExecutor executor;

    @Mock
    private GitMirrorStore mirrors;

    @Mock
    private NamespaceMemberRepository namespaceMemberRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private MessageSource messageSource;

    private GitSyncAdminService service;

    /**
     * Address policy is covered by {@link GitSyncUrlPolicyTest}; these tests are
     * about source handling, so every host they use is allowed here.
     */
    private static GitSyncUrlPolicy allowAllHosts() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.getUrl().setAllowedHosts(List.of("github.com", "gitlab.internal", "gitea.internal"));
        return new GitSyncUrlPolicy(properties);
    }

    @BeforeEach
    void setUp() {
        service = new GitSyncAdminService(store, namespaceRepository, planService, executor, mirrors,
                allowAllHosts(), new GitSyncProperties(), namespaceMemberRepository, auditLogService,
                new ObjectMapper(), messageSource,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Only the source list asks for per-source counts.
        lenient().when(store.countImportedBySource()).thenReturn(new HashMap<>());
    }

    private static GitSourceCreateRequest request(String repoUrl, String credential, Integer interval) {
        return new GitSourceCreateRequest(repoUrl, null, null, "global",
                credential, interval, null);
    }

    private void givenNamespaceExists(String slug) {
        givenNamespaceExists(slug, NamespaceType.GLOBAL);
    }

    private void givenNamespaceExists(String slug, NamespaceType type) {
        Namespace namespace = new Namespace(slug, "Global", "owner-1");
        namespace.setType(type);
        given(namespaceRepository.findBySlug(slug)).willReturn(Optional.of(namespace));
        // The publisher (= creator) has to be a member, which the publish step checks.
        lenient().when(namespaceMemberRepository.findByNamespaceIdAndUserId(any(), any()))
                .thenReturn(Optional.of(new NamespaceMember(namespace.getId(), "admin-1", NamespaceRole.OWNER)));
    }

    private void givenPublisherIsNotAMember(String slug) {
        given(namespaceRepository.findBySlug(slug)).willReturn(Optional.of(new Namespace(slug, "Global", "owner-1")));
        lenient().when(namespaceMemberRepository.findByNamespaceIdAndUserId(any(), any()))
                .thenReturn(Optional.empty());
    }

    private GitSourceRecord captureUpdated() {
        ArgumentCaptor<GitSourceRecord> captor = ArgumentCaptor.forClass(GitSourceRecord.class);
        then(store).should().update(captor.capture());
        return captor.getValue();
    }

    private GitSourceRecord captureInserted() {
        ArgumentCaptor<GitSourceRecord> captor = ArgumentCaptor.forClass(GitSourceRecord.class);
        then(store).should().insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void createDerivesCoordinatesAndDefaults() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        GitSourceResponse response = service.create(
                request("https://github.com/owner/repo.git", null, null), "admin-1", null);

        GitSourceRecord inserted = captureInserted();
        assertEquals("owner/repo", inserted.repoPath());
        // The URL is stored exactly as configured: git clones this remote.
        assertEquals("https://github.com/owner/repo.git", inserted.repoUrl());
        // No ref means "the repository's default branch", decided at sync time;
        // nothing is guessed here.
        assertEquals("", inserted.defaultRef());
        assertEquals("", inserted.subpath());
        assertEquals(60, inserted.syncIntervalMinutes());
        assertEquals("admin-1", inserted.publisherId());
        assertEquals("admin-1", inserted.createdBy());
        assertTrue(inserted.enabled());

        assertEquals(5L, response.id());
        assertEquals("https://github.com/owner/repo", response.webUrl());
    }

    @Test
    void createKeepsRepositoryUrlAndDerivesPathForSelfHostedInstances() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.create(request("https://gitlab.internal/group/sub/repo.git", "gitlab-internal", null), "admin-1", null);

        GitSourceRecord inserted = captureInserted();
        assertEquals("https://gitlab.internal/group/sub/repo.git", inserted.repoUrl());
        assertEquals("group/sub/repo", inserted.repoPath());
    }

    @Test
    void createAcceptsSshRemoteAndRecordsTheCallerAsPublisher() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.create(new GitSourceCreateRequest("git@gitea.internal:owner/repo.git",
                "release", "skills", "global", "gitea-token",
                30, false), "admin-1", null);

        GitSourceRecord inserted = captureInserted();
        assertEquals("git@gitea.internal:owner/repo.git", inserted.repoUrl());
        assertEquals("owner/repo", inserted.repoPath());
        assertEquals("release", inserted.defaultRef());
        assertEquals("skills", inserted.subpath());
        // The publisher is the caller; the request cannot name somebody else.
        assertEquals("admin-1", inserted.publisherId());
        assertEquals(30, inserted.syncIntervalMinutes());
        assertFalse(inserted.enabled());
        // The namespace decides visibility, exactly like the manual publish form.
        assertEquals(SkillVisibility.PUBLIC, inserted.defaultVisibility());
    }

    @Test
    void importsIntoATeamNamespaceAsNamespaceOnly() {
        givenNamespaceExists("team-a", NamespaceType.TEAM);
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.create(new GitSourceCreateRequest("https://github.com/owner/repo", null, null,
                "team-a", null, 60, true), "admin-1", null);

        assertEquals(SkillVisibility.NAMESPACE_ONLY, captureInserted().defaultVisibility());
    }

    @Test
    void movingASourceToAnotherNamespaceReDerivesItsVisibility() {
        GitSourceRecord existing = new GitSourceRecord(5L, "https://github.com/owner/repo", "owner/repo",
                "main", "", "global", null, "admin-1", 60, true, SkillVisibility.PUBLIC,
                "admin-1", NOW, NOW, null, null, null, null);
        given(store.findById(5L)).willReturn(Optional.of(existing));
        givenNamespaceExists("team-a", NamespaceType.TEAM);
        given(store.findByLocation(any(), any(), any())).willReturn(Optional.of(existing));

        service.update(5L, new GitSourceCreateRequest("https://github.com/owner/repo", null, null,
                "team-a", null, 60, true), "admin-1", true, null);

        ArgumentCaptor<GitSourceRecord> updated = ArgumentCaptor.forClass(GitSourceRecord.class);
        verify(store).update(updated.capture());
        assertEquals("team-a", updated.getValue().namespaceSlug());
        assertEquals(SkillVisibility.NAMESPACE_ONLY, updated.getValue().defaultVisibility());
    }



    @Test
    void createTreatsAnEmptyCredentialReferenceAsAPublicRepository() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.create(request("https://github.com/owner/repo", "   ", null), "admin-1", null);

        assertNull(captureInserted().credential());
    }

    @Test
    void createRejectsUrlWithoutRepositoryPath() {
        assertThrows(DomainBadRequestException.class, () -> service.create(
                request("github.com/owner/repo", null, null), "admin-1", null));
    }

    @Test
    void createRejectsOutOfRangeInterval() {
        assertThrows(DomainBadRequestException.class, () -> service.create(
                request("https://github.com/owner/repo", null, 1), "admin-1", null));
        assertThrows(DomainBadRequestException.class, () -> service.create(
                request("https://github.com/owner/repo", null, 60 * 24 * 365), "admin-1", null));
    }

    @Test
    void createRejectsUnknownNamespace() {
        given(namespaceRepository.findBySlug("global")).willReturn(Optional.empty());

        assertThrows(DomainNotFoundException.class, () -> service.create(
                request("https://github.com/owner/repo", null, null), "admin-1", null));
    }


    @Test
    void planResponseLocalizesThePredictedFailures() {
        GitSourceRecord source = record(5L);
        given(store.findById(5L)).willReturn(Optional.of(source));
        given(planService.plan(source)).willReturn(new GitImportPlan(5L, "main", "c".repeat(40), "",
                List.of(new GitImportPlanItem("skills/pdf", "PDF Helper", "tree-1",
                        GitImportPlanItem.ChangeType.ADDED, null, null, 2, List.of(),
                        List.of(PlanProblem.of("error.skill.publish.nameConflict", "pdf-helper")))),
                NOW));
        given(messageSource.getMessage(eq("error.skill.publish.nameConflict"), any(), anyString(), any()))
                .willReturn("已发布同名技能：pdf-helper");

        var response = service.plan(5L, "admin-1", true);

        assertEquals(1, response.failing());
        assertEquals(List.of("已发布同名技能：pdf-helper"), response.items().getFirst().problems());
    }


    @Test
    void listSurvivesASourceWhoseStoredUrlCannotBeParsed() {
        // A row written before validation (or by a script) must not break the list.
        GitSourceRecord broken = new GitSourceRecord(9L, "github.com/example/demo", "example/demo",
                "main", "skills", "global", null, "docker-admin", 60, true, SkillVisibility.PUBLIC,
                "docker-admin", NOW, NOW, null, null, null, null);
        given(store.findAll()).willReturn(List.of(broken));
        given(store.countImportedBySource()).willReturn(java.util.Map.of());

        List<GitSourceResponse> sources = service.list("docker-admin", true);

        assertEquals(1, sources.size());
        assertNull(sources.getFirst().webUrl());
    }

    @Test
    void planDelegatesToPlannerAndRecordsPlanningTime() {
        GitSourceRecord source = record(5L);
        given(store.findById(5L)).willReturn(Optional.of(source));
        given(planService.plan(source)).willReturn(new GitImportPlan(5L, "main", "c".repeat(40), "",
                List.of(new GitImportPlanItem("skills/pdf", "PDF Helper", "tree-1",
                        GitImportPlanItem.ChangeType.ADDED, null, null, 3,
                        List.of(SkippedGitFile.of("LICENSE", "gitsync.skip.fileType")), List.of())),
                NOW));

        var response = service.plan(5L, "admin-1", true);

        then(planService).should().plan(source);
        then(store).should().touchLastPlanned(5L);
        assertEquals(5L, response.sourceId());
        assertEquals(1, response.added());
        assertEquals(0, response.removed());
        assertEquals("skills/pdf", response.items().getFirst().itemPath());
        assertEquals("ADDED", response.items().getFirst().changeType());
        assertEquals(3, response.items().getFirst().fileCount());
        assertEquals("LICENSE", response.items().getFirst().skippedFiles().getFirst().path());
    }

    @Test
    void deleteRemovesTheSourceAndItsMirror() {
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.delete(5L, "admin-1", true, null);

        then(store).should().delete(5L);
        then(mirrors).should().delete(5L);
    }

    @Test
    void storesADeployTokenExactlyAsTyped() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L, "release-bot:glpat-secret")));

        GitSourceResponse response = service.create(
                request("https://gitlab.internal/group/repo.git", "release-bot:glpat-secret", null), "admin-1", null);

        assertEquals("release-bot:glpat-secret", captureInserted().credential());
        // The value never travels back to the browser; only its presence and kind.
        assertTrue(response.hasCredential());
        assertEquals("TOKEN", response.credentialKind());
    }

    @Test
    void storesAPastedPrivateKeyForSshRemotes() {
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----";
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L, "git@gitlab.internal:group/repo.git", key)));

        GitSourceResponse response = service.create(
                request("git@gitlab.internal:group/repo.git", key, null), "admin-1", null);

        assertEquals(key, captureInserted().credential());
        assertTrue(response.hasCredential());
        assertEquals("SSH_KEY", response.credentialKind());
    }

    @Test
    void rejectsAPublicKeyOrAPathForSshRemotes() {
        assertThrows(DomainBadRequestException.class, () -> service.create(
                request("git@gitlab.internal:group/repo.git", "ssh-ed25519 AAAAC3Nza public", null), "admin-1", null));
        assertThrows(DomainBadRequestException.class, () -> service.create(
                request("git@gitlab.internal:group/repo.git", "/etc/skillhub/id_ed25519", null), "admin-1", null));
    }

    @Test
    void reportsNoCredentialForPublicRepositories() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        GitSourceResponse response = service.create(
                request("https://github.com/owner/repo", "  ", null), "admin-1", null);

        assertNull(captureInserted().credential());
        assertFalse(response.hasCredential());
        assertNull(response.credentialKind());
    }

    @Test
    void updateKeepsTheStoredCredentialWhenTheFieldIsLeftEmpty() {
        givenNamespaceExists("global");
        given(store.findById(5L)).willReturn(Optional.of(record(5L, "release-bot:glpat-secret")));

        service.update(5L, request("https://github.com/owner/repo", "", null), "admin-1", true, null);

        assertEquals("release-bot:glpat-secret", captureUpdated().credential());
    }

    @Test
    void updateClearsTheStoredCredentialWithASingleDash() {
        givenNamespaceExists("global");
        given(store.findById(5L)).willReturn(Optional.of(record(5L, "release-bot:glpat-secret")));

        service.update(5L, request("https://github.com/owner/repo", "-", null), "admin-1", true, null);

        assertNull(captureUpdated().credential());
    }

    @Test
    void listReportsImportedSkillCountPerSource() {
        given(store.findAll()).willReturn(List.of(record(5L), record(6L)));
        given(store.countImportedBySource()).willReturn(Map.of(5L, 18, 6L, 2));

        List<GitSourceResponse> sources = service.list("admin-1", true);

        assertEquals(18, sources.get(0).importedSkillCount());
        assertEquals(2, sources.get(1).importedSkillCount());
    }

    @Test
    void listReportsZeroForASourceThatImportedNothingYet() {
        given(store.findAll()).willReturn(List.of(record(7L)));
        given(store.countImportedBySource()).willReturn(Map.of());

        assertEquals(0, service.list("admin-1", true).get(0).importedSkillCount());
    }

    @Test
    void getRejectsUnknownSource() {
        given(store.findById(404L)).willReturn(Optional.empty());

        assertThrows(DomainNotFoundException.class, () -> service.get(404L, "admin-1", true));
    }

    @Test
    void createRejectsALocationThatIsAlreadyRegistered() {
        givenNamespaceExists("global");
        given(store.findByLocation("https://github.com/owner/repo", "", "global"))
                .willReturn(Optional.of(record(9L, "https://github.com/owner/repo", null)));

        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> service.create(request("https://github.com/owner/repo", null, null), "admin-1", null));

        // A duplicate is an administrator mistake, not a server failure.
        assertEquals("error.gitSource.duplicate", error.messageCode());
        assertEquals("owner/repo", error.messageArgs()[0]);
        assertEquals("global", error.messageArgs()[1]);
        verify(store, never()).insert(any());
    }

    @Test
    void updateRejectsTakingOverAnotherSourcesLocation() {
        given(store.findById(5L)).willReturn(Optional.of(record(5L, "https://github.com/owner/repo", null)));
        givenNamespaceExists("global");
        given(store.findByLocation("https://gitlab.internal/group/repo", "", "global"))
                .willReturn(Optional.of(record(9L, "https://gitlab.internal/group/repo", null)));

        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> service.update(5L, request("https://gitlab.internal/group/repo", null, null), "admin-1", true, null));

        assertEquals("error.gitSource.duplicate", error.messageCode());
        verify(store, never()).update(any());
    }

    @Test
    void updateAcceptsTheSourcesOwnLocation() {
        given(store.findById(5L)).willReturn(Optional.of(record(5L, "https://github.com/owner/repo", null)));
        givenNamespaceExists("global");
        given(store.findByLocation("https://github.com/owner/repo", "", "global"))
                .willReturn(Optional.of(record(5L, "https://github.com/owner/repo", null)));

        service.update(5L, request("https://github.com/owner/repo", null, null), "admin-1", true, null);

        verify(store).update(any());
    }

    @Test
    void createAuditsTheActionWithoutTheCredentialValue() {
        givenNamespaceExists("global");
        given(store.insert(any())).willReturn(5L);
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.create(request("https://github.com/owner/repo", "release-bot:glpat-secret", null),
                "admin-1", new AuditRequestContext("10.0.0.9", "junit"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq("admin-1"), eq("GIT_SOURCE_CREATE"), eq("GIT_SOURCE"), eq(5L),
                any(), eq("10.0.0.9"), eq("junit"), detail.capture());
        assertTrue(detail.getValue().contains("\"repoPath\":\"owner/repo\""));
        assertTrue(detail.getValue().contains("\"credentialKind\":\"TOKEN\""));
        // The secret itself must never reach the audit log.
        assertFalse(detail.getValue().contains("glpat-secret"));
    }

    @Test
    void updateAuditsOnlyHowTheCredentialChanged() {
        // First read is the stored source, second one is the updated row the
        // response is built from.
        given(store.findById(5L)).willReturn(
                Optional.of(record(5L, "release-bot:glpat-old")),
                Optional.of(record(5L, "release-bot:glpat-new")));
        givenNamespaceExists("global");
        given(store.findByLocation(any(), any(), any()))
                .willReturn(Optional.of(record(5L, "release-bot:glpat-old")));

        service.update(5L, request("https://github.com/owner/repo", "release-bot:glpat-new", null),
                "admin-1", true, null);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq("admin-1"), eq("GIT_SOURCE_UPDATE"), eq("GIT_SOURCE"), eq(5L),
                any(), any(), any(), detail.capture());
        assertTrue(detail.getValue().contains("\"credentialChange\":\"SET_TOKEN\""));
        assertFalse(detail.getValue().contains("glpat-new"));
    }

    @Test
    void deleteAuditsTheRemovedSource() {
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));

        service.delete(5L, "admin-1", true, null);

        verify(auditLogService).record(eq("admin-1"), eq("GIT_SOURCE_DELETE"), eq("GIT_SOURCE"), eq(5L),
                any(), any(), any(), any());
    }

    @Test
    void syncAuditsWhoAskedForIt() {
        given(store.findById(5L)).willReturn(Optional.of(record(5L)));
        GitSyncRunRecord run = new GitSyncRunRecord(77L, 5L, "MANUAL", "SUCCESS", 3, 1, 1, 1, 0, 0, null, NOW, NOW);
        given(executor.sync(5L, "MANUAL")).willReturn(run);

        service.sync(5L, "admin-1", true, null);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq("admin-1"), eq("GIT_SOURCE_SYNC"), eq("GIT_SOURCE"), eq(5L),
                any(), any(), any(), detail.capture());
        assertTrue(detail.getValue().contains("\"runId\":77"));
        assertTrue(detail.getValue().contains("\"status\":\"SUCCESS\""));
    }

    @Test
    void listShowsARegularUserOnlyTheirOwnSources() {
        GitSourceRecord mine = recordOwnedBy(5L, "user-1");
        given(store.findByCreatedBy("user-1")).willReturn(List.of(mine));
        given(store.countImportedBySource()).willReturn(Map.of(5L, 3));

        List<GitSourceResponse> sources = service.list("user-1", false);

        assertEquals(1, sources.size());
        assertEquals(5L, sources.getFirst().id());
        then(store).should(never()).findAll();
    }

    @Test
    void listShowsAnAdministratorEverySource() {
        given(store.findAll()).willReturn(List.of(recordOwnedBy(5L, "user-1"), recordOwnedBy(6L, "user-2")));
        given(store.countImportedBySource()).willReturn(Map.of());

        assertEquals(2, service.list("admin-1", true).size());
        then(store).should(never()).findByCreatedBy(any());
    }

    @Test
    void aRegularUserCannotSeeOrChangeSomebodyElsesSource() {
        given(store.findById(5L)).willReturn(Optional.of(recordOwnedBy(5L, "user-1")));

        // Reported as missing, not as forbidden: the API must not confirm that
        // another user's source exists.
        for (var call : List.<org.junit.jupiter.api.function.Executable>of(
                () -> service.get(5L, "user-2", false),
                () -> service.items(5L, "user-2", false),
                () -> service.runs(5L, 10, "user-2", false),
                () -> service.plan(5L, "user-2", false),
                () -> service.delete(5L, "user-2", false, null),
                () -> service.sync(5L, "user-2", false, null),
                () -> service.update(5L, request("https://github.com/owner/repo", null, null),
                        "user-2", false, null))) {
            DomainNotFoundException error = assertThrows(DomainNotFoundException.class, call);
            assertEquals("error.gitSource.notFound", error.messageCode());
        }
        verify(store, never()).delete(any());
        verify(store, never()).update(any());
    }

    @Test
    void anAdministratorCanChangeAnybodysSource() {
        given(store.findById(5L)).willReturn(Optional.of(recordOwnedBy(5L, "user-1")));

        service.get(5L, "admin-1", true);
        service.delete(5L, "admin-1", true, null);

        verify(store).delete(5L);
    }

    @Test
    void aRegularUserCanChangeTheirOwnSource() {
        given(store.findById(5L)).willReturn(Optional.of(recordOwnedBy(5L, "user-1")));

        service.delete(5L, "user-1", false, null);

        verify(store).delete(5L);
    }

    @Test
    void rejectsASourceWhenThePublisherIsNotAMemberOfTheNamespace() {
        givenPublisherIsNotAMember("global");

        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> service.create(request("https://github.com/owner/repo", null, null), "admin-1", null));

        assertEquals("error.skill.publish.publisher.notMember", error.messageCode());
        verify(store, never()).insert(any());
    }

    @Test
    void rejectsASourceOverThePerUserQuota() {
        givenNamespaceExists("global");
        GitSyncProperties limited = new GitSyncProperties();
        limited.getLimits().setMaxSourcesPerUser(1);
        GitSyncAdminService quotaService = new GitSyncAdminService(store, namespaceRepository, planService,
                executor, mirrors, allowAllHosts(), limited, namespaceMemberRepository, auditLogService,
                new ObjectMapper(), messageSource, Clock.fixed(NOW, ZoneOffset.UTC));
        given(store.countByCreatedBy("admin-1")).willReturn(1);

        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> quotaService.create(request("https://github.com/owner/repo", null, null), "admin-1", null));

        assertEquals("error.gitSource.limit.sources", error.messageCode());
        assertEquals(1, error.messageArgs()[0]);
        verify(store, never()).insert(any());
    }

    private static GitSourceRecord recordOwnedBy(Long id, String createdBy) {
        return new GitSourceRecord(id, "https://github.com/owner/repo", "owner/repo",
                "main", "", "global", null, createdBy, 60, true, SkillVisibility.PUBLIC,
                createdBy, NOW, NOW, null, null, null, null);
    }

    private static GitSourceRecord record(Long id) {
        return record(id, null);
    }

    private static GitSourceRecord record(Long id, String credential) {
        return record(id, "https://github.com/owner/repo", credential);
    }

    private static GitSourceRecord record(Long id, String repoUrl, String credential) {
        return new GitSourceRecord(id, repoUrl, "owner/repo",
                "main", "", "global", credential, "admin-1", 60, true, SkillVisibility.PUBLIC,
                "admin-1", NOW, NOW, null, null, null, null);
    }
}
