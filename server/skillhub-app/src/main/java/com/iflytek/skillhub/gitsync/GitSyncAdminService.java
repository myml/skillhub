package com.iflytek.skillhub.gitsync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.gitsync.dto.GitImportPlanResponse;
import com.iflytek.skillhub.gitsync.dto.GitSourceCreateRequest;
import com.iflytek.skillhub.gitsync.dto.GitSourceItemResponse;
import com.iflytek.skillhub.gitsync.dto.GitSourceResponse;
import com.iflytek.skillhub.gitsync.dto.GitSyncRunResponse;
import com.iflytek.skillhub.gitsync.git.GitMirrorStore;
import com.iflytek.skillhub.gitsync.git.GitProviderException;
import com.iflytek.skillhub.gitsync.git.GitRepoUrls;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.GitSyncRunRecord;
import com.iflytek.skillhub.service.AuditRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Administrator-facing management of git sources.
 */
@Service
public class GitSyncAdminService {

    /** Clearing a stored credential, since the form never echoes it back. */
    static final String CREDENTIAL_CLEAR = "-";

    static final int DEFAULT_SYNC_INTERVAL_MINUTES = 60;
    static final int MIN_SYNC_INTERVAL_MINUTES = 5;
    static final int MAX_SYNC_INTERVAL_MINUTES = 10_080;
    static final int DEFAULT_RUN_HISTORY_LIMIT = 20;
    static final int MAX_RUN_HISTORY_LIMIT = 200;

    private final GitSourceStore store;
    private final NamespaceRepository namespaceRepository;
    private final GitSourcePlanService planService;
    private final GitSyncExecutor executor;
    private final GitMirrorStore mirrors;
    private final GitSyncUrlPolicy urlPolicy;
    private final GitSyncProperties properties;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GitSyncAdminService(GitSourceStore store,
                               NamespaceRepository namespaceRepository,
                               GitSourcePlanService planService,
                               GitSyncExecutor executor,
                               GitMirrorStore mirrors,
                               GitSyncUrlPolicy urlPolicy,
                               GitSyncProperties properties,
                               NamespaceMemberRepository namespaceMemberRepository,
                               AuditLogService auditLogService,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.store = store;
        this.namespaceRepository = namespaceRepository;
        this.planService = planService;
        this.executor = executor;
        this.mirrors = mirrors;
        this.urlPolicy = urlPolicy;
        this.properties = properties;
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public GitSourceResponse create(GitSourceCreateRequest request, String actorId,
                                    AuditRequestContext auditContext) {
        ValidatedSource validated = validate(request);
        Namespace namespace = requireNamespace(validated.namespaceSlug());
        requireLocationFree(validated, null);
        // The creator publishes, so it has to be able to: without the membership the
        // publish step rejects every version the source would import.
        requirePublishableBy(namespace, actorId);
        requireSourceQuota(actorId);
        SkillVisibility visibility = defaultVisibilityFor(namespace);

        Instant now = Instant.now(clock);
        GitSourceRecord record = new GitSourceRecord(
                null,
                validated.repoUrl(),
                validated.repoPath(),
                validated.defaultRef(),
                validated.subpath(),
                validated.namespaceSlug(),
                validated.credential(),
                // The creator publishes, so nobody can push content into a namespace
                // under another user's name.
                actorId,
                validated.syncIntervalMinutes(),
                request.enabled() == null || request.enabled(),
                visibility,
                actorId,
                now,
                now,
                null,
                null,
                null,
                null
        );
        Long id = insert(record);
        recordAudit("GIT_SOURCE_CREATE", actorId, id, auditContext, detailOf(record));
        return toResponse(requireSource(id), 0);
    }

    @Transactional
    public GitSourceResponse update(Long id, GitSourceCreateRequest request, String actorId,
                                    boolean canManageAll, AuditRequestContext auditContext) {
        GitSourceRecord existing = requireAccessibleSource(id, actorId, canManageAll);
        ValidatedSource validated = validate(request);
        Namespace namespace = requireNamespace(validated.namespaceSlug());
        requireLocationFree(validated, id);
        requirePublishableBy(namespace, existing.publisherId());
        // Re-derived on every edit: moving a source to another namespace moves its
        // visibility with it.
        SkillVisibility visibility = defaultVisibilityFor(namespace);

        GitSourceRecord updated = new GitSourceRecord(
                id,
                validated.repoUrl(),
                validated.repoPath(),
                validated.defaultRef(),
                validated.subpath(),
                validated.namespaceSlug(),
                // The form never receives the stored credential, so an empty field
                // means "keep it" and a single '-' clears it.
                validated.credentialProvided() ? validated.credential() : existing.credential(),
                // Who publishes is fixed when the source is created.
                existing.publisherId(),
                validated.syncIntervalMinutes(),
                request.enabled() == null ? existing.enabled() : request.enabled(),
                visibility,
                existing.createdBy(),
                existing.createdAt(),
                Instant.now(clock),
                existing.lastPlannedAt(),
                existing.lastSyncAt(),
                existing.lastSyncStatus(),
                existing.lastSyncMessage()
        );
        store.update(updated);
        recordAudit("GIT_SOURCE_UPDATE", actorId, id, auditContext,
                detailOf(updated, credentialChange(existing, updated)));
        return toResponse(requireSource(id), countImported(id));
    }

    /**
     * A location can only be registered once; saying so beats letting the unique
     * key surface as a 500, which is what happens when an administrator submits
     * the same repository twice.
     */
    private void requireLocationFree(ValidatedSource validated, Long selfId) {
        store.findByLocation(validated.repoUrl(), validated.subpath(), validated.namespaceSlug())
                .filter(found -> !found.id().equals(selfId))
                .ifPresent(found -> {
                    throw new DomainBadRequestException("error.gitSource.duplicate",
                            validated.repoPath(), validated.namespaceSlug());
                });
    }

    /**
     * The check above closes the normal path; this also closes the race between
     * two concurrent registrations of the same location.
     */
    private Long insert(GitSourceRecord record) {
        try {
            return store.insert(record);
        } catch (DataIntegrityViolationException ex) {
            throw new DomainBadRequestException("error.gitSource.duplicate",
                    record.repoPath(), record.namespaceSlug());
        }
    }

    @Transactional(readOnly = true)
    public List<GitSourceResponse> list(String actorId, boolean canManageAll) {
        Map<Long, Integer> imported = store.countImportedBySource();
        return sourcesVisibleTo(actorId, canManageAll).stream()
                .map(source -> toResponse(source, imported.getOrDefault(source.id(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public GitSourceResponse get(Long id, String actorId, boolean canManageAll) {
        return toResponse(requireAccessibleSource(id, actorId, canManageAll), countImported(id));
    }

    @Transactional
    public void delete(Long id, String actorId, boolean canManageAll, AuditRequestContext auditContext) {
        GitSourceRecord existing = requireAccessibleSource(id, actorId, canManageAll);
        store.delete(id);
        // The mirror is a cache of this source; drop it together with the source.
        mirrors.delete(id);
        recordAudit("GIT_SOURCE_DELETE", actorId, id, auditContext, detailOf(existing));
    }

    @Transactional(readOnly = true)
    public List<GitSourceItemResponse> items(Long id, String actorId, boolean canManageAll) {
        requireAccessibleSource(id, actorId, canManageAll);
        return store.findItems(id).stream().map(GitSyncAdminService::toItemResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<GitSyncRunResponse> runs(Long id, Integer limit, String actorId, boolean canManageAll) {
        requireAccessibleSource(id, actorId, canManageAll);
        int effective = limit == null ? DEFAULT_RUN_HISTORY_LIMIT : Math.clamp(limit, 1, MAX_RUN_HISTORY_LIMIT);
        return store.findRuns(id, effective).stream().map(GitSyncAdminService::toRunResponse).toList();
    }

    /**
     * Dry-run: scans the repository and reports what a sync would change without
     * importing anything. Skipped files are listed so the effect is predictable.
     */
    @Transactional
    public GitImportPlanResponse plan(Long id, String actorId, boolean canManageAll) {
        GitSourceRecord source = requireAccessibleSource(id, actorId, canManageAll);
        GitImportPlan plan = planService.plan(source);
        store.touchLastPlanned(id);
        return toPlanResponse(plan);
    }

    /**
     * Runs a sync now, in the caller's request thread.
     */
    public GitSyncRunResponse sync(Long id, String actorId, boolean canManageAll,
                                   AuditRequestContext auditContext) {
        GitSourceRecord source = requireAccessibleSource(id, actorId, canManageAll);
        GitSyncRunRecord run = executor.sync(id, GitSyncExecutor.TRIGGER_MANUAL);
        // Scheduled ticks are covered by the run history; this records who asked.
        Map<String, Object> detail = detailOf(source);
        detail.put("runId", run.id());
        detail.put("status", run.status());
        detail.put("planned", run.plannedCount());
        detail.put("failed", run.failedCount());
        recordAudit("GIT_SOURCE_SYNC", actorId, id, auditContext, detail);
        return toRunResponse(run);
    }

    /**
     * Sources the actor is allowed to see: administrators see every source, a
     * regular user only the ones they registered themselves.
     */
    private List<GitSourceRecord> sourcesVisibleTo(String actorId, boolean canManageAll) {
        return canManageAll ? store.findAll() : store.findByCreatedBy(actorId);
    }

    /**
     * Loads a source the actor may read or change.
     *
     * <p>Somebody else's source is reported as missing rather than forbidden: a
     * regular user has no business learning that it exists, and the API must not
     * become a way to enumerate other people's repositories.
     */
    private GitSourceRecord requireAccessibleSource(Long id, String actorId, boolean canManageAll) {
        GitSourceRecord source = requireSource(id);
        if (!canManageAll && !Objects.equals(source.createdBy(), actorId)) {
            throw new DomainNotFoundException("error.gitSource.notFound", id);
        }
        return source;
    }

    /** Each user may register at most the configured number of sources. */
    private void requireSourceQuota(String actorId) {
        int max = properties.getLimits().getMaxSourcesPerUser();
        if (max > 0 && store.countByCreatedBy(actorId) >= max) {
            throw new DomainBadRequestException("error.gitSource.limit.sources", max);
        }
    }

    /**
     * Records an administrative action. The credential value is never part of the
     * audit trail; only its kind and whether it changed.
     */
    private void recordAudit(String action, String actorId, Long targetId,
                            AuditRequestContext context, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            json = "{}";
        }
        auditLogService.record(
                actorId,
                action,
                "GIT_SOURCE",
                targetId,
                MDC.get("requestId"),
                context != null ? context.clientIp() : null,
                context != null ? context.userAgent() : null,
                json
        );
    }

    private static Map<String, Object> detailOf(GitSourceRecord source) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("repoUrl", source.repoUrl());
        detail.put("repoPath", source.repoPath());
        detail.put("subpath", source.subpath());
        detail.put("namespaceSlug", source.namespaceSlug());
        detail.put("defaultRef", source.defaultRef());
        detail.put("syncIntervalMinutes", source.syncIntervalMinutes());
        detail.put("enabled", source.enabled());
        detail.put("defaultVisibility", source.defaultVisibility().name());
        detail.put("credentialKind", credentialKind(source));
        return detail;
    }

    private static Map<String, Object> detailOf(GitSourceRecord source, String credentialChange) {
        Map<String, Object> detail = detailOf(source);
        detail.put("credentialChange", credentialChange);
        return detail;
    }

    /** {@code SET_TOKEN}, {@code SET_SSH_KEY}, {@code CLEARED} or {@code UNCHANGED}. */
    private static String credentialChange(GitSourceRecord before, GitSourceRecord after) {
        if (after.credential() == null) {
            return before.credential() == null ? "UNCHANGED" : "CLEARED";
        }
        if (after.credential().equals(before.credential())) {
            return "UNCHANGED";
        }
        return isSshRemote(after.repoUrl()) ? "SET_SSH_KEY" : "SET_TOKEN";
    }

    /**
     * The publisher of a source has to belong to the target namespace, because
     * publishing does not grant any platform role that could bypass that check.
     */
    private void requirePublishableBy(Namespace namespace, String publisherId) {
        namespaceMemberRepository.findByNamespaceIdAndUserId(namespace.getId(), publisherId)
                .orElseThrow(() -> new DomainBadRequestException(
                        "error.skill.publish.publisher.notMember", namespace.getSlug()));
    }

    private GitSourceRecord requireSource(Long id) {
        return store.findById(id)
                .orElseThrow(() -> new DomainNotFoundException("error.gitSource.notFound", id));
    }

    private Namespace requireNamespace(String namespaceSlug) {
        return namespaceRepository.findBySlug(namespaceSlug)
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.slug.notFound", namespaceSlug));
    }

    /**
     * Matches the manual publish flow: the namespace decides who may see the
     * skill, so an imported skill gets the same visibility it would have gotten
     * from the upload form.
     */
    private static SkillVisibility defaultVisibilityFor(Namespace namespace) {
        return namespace.getType() == NamespaceType.GLOBAL
                ? SkillVisibility.PUBLIC
                : SkillVisibility.NAMESPACE_ONLY;
    }

    private ValidatedSource validate(GitSourceCreateRequest request) {
        String repoUrl;
        String repoPath;
        try {
            // Keep the URL as typed: git needs the HTTPS or SSH remote, and the
            // browsable URL is derived from it when a link is rendered.
            repoUrl = request.repoUrl().trim();
            repoPath = GitRepoUrls.repoPath(repoUrl);
        } catch (GitProviderException ex) {
            throw new DomainBadRequestException("error.gitSource.invalid", ex.getMessage());
        }
        // Rejecting an unreachable host while the administrator is still on the
        // form beats failing silently on the next scheduled run.
        urlPolicy.requireAllowed(repoUrl);

        String credential = normalizeCredential(request.credential(), repoUrl);
        boolean credentialProvided = StringUtils.hasText(request.credential());

        int interval = request.syncIntervalMinutes() == null
                ? DEFAULT_SYNC_INTERVAL_MINUTES
                : request.syncIntervalMinutes();
        if (interval < MIN_SYNC_INTERVAL_MINUTES || interval > MAX_SYNC_INTERVAL_MINUTES) {
            throw new DomainBadRequestException("error.gitSource.interval.invalid",
                    MIN_SYNC_INTERVAL_MINUTES, MAX_SYNC_INTERVAL_MINUTES);
        }

        return new ValidatedSource(
                repoUrl,
                repoPath,
                StringUtils.hasText(request.defaultRef()) ? request.defaultRef().trim() : "main",
                request.subpath() == null ? "" : request.subpath().trim(),
                request.namespaceSlug().trim(),
                credential,
                credentialProvided,
                interval
        );
    }


    /**
     * The credential is stored exactly as typed, because the value is what the sync
     * sends: {@code <user>:<deploy token>} for HTTP(S) remotes, the private key
     * itself for SSH remotes. Empty means a public repository.
     *
     * <p>On update, an empty value keeps the stored credential and a single
     * {@code -} clears it, so an administrator can edit a source without the secret
     * ever being sent back to the browser.
     */
    static String normalizeCredential(String submitted, String repoUrl) {
        if (submitted == null) {
            return null;
        }
        String value = submitted.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (CREDENTIAL_CLEAR.equals(value)) {
            return null;
        }
        if (isSshRemote(repoUrl)) {
            String firstLine = value.lines().findFirst().orElse("").trim();
            boolean looksLikePublicKey = firstLine.startsWith("ssh-") || firstLine.startsWith("ecdsa-sha2-");
            boolean looksLikePath = !value.contains("\n")
                    && (value.startsWith("/") || value.startsWith("~") || value.startsWith("ssh:"));
            if (looksLikePublicKey || looksLikePath) {
                throw new DomainBadRequestException("error.gitSource.credential.notPrivateKey");
            }
        }
        return submitted;
    }

    /** What the UI may show about the stored credential: never the value itself. */
    private static String credentialKind(GitSourceRecord source) {
        if (source.credential() == null) {
            return null;
        }
        return isSshRemote(source.repoUrl()) ? "SSH_KEY" : "TOKEN";
    }

    private static boolean isSshRemote(String repoUrl) {
        return GitCredentialResolver.isSshRemote(repoUrl);
    }

    private int countImported(Long sourceId) {
        return store.countImportedBySource().getOrDefault(sourceId, 0);
    }

    static GitSourceResponse toResponse(GitSourceRecord source, int importedSkillCount) {
        return new GitSourceResponse(
                source.id(),
                source.repoUrl(),
                GitRepoUrls.webUrl(source.repoUrl()),
                source.repoPath(),
                source.defaultRef(),
                source.subpath(),
                source.namespaceSlug(),
                importedSkillCount,
                source.credential() != null,
                credentialKind(source),
                source.publisherId(),
                source.syncIntervalMinutes(),
                source.enabled(),
                source.defaultVisibility().name(),
                source.createdBy(),
                source.createdAt(),
                source.updatedAt(),
                source.lastPlannedAt(),
                source.lastSyncAt(),
                source.lastSyncStatus(),
                source.lastSyncMessage()
        );
    }

    static GitSourceItemResponse toItemResponse(GitSourceItemRecord item) {
        return new GitSourceItemResponse(
                item.itemPath(),
                item.skillName(),
                item.treeSha(),
                item.commitSha(),
                item.state(),
                item.skillId(),
                item.lastSeenAt(),
                item.lastSyncedAt()
        );
    }

    static GitSyncRunResponse toRunResponse(GitSyncRunRecord run) {
        return new GitSyncRunResponse(
                run.id(),
                run.sourceId(),
                run.triggerType(),
                run.status(),
                run.plannedCount(),
                run.addedCount(),
                run.changedCount(),
                run.unchangedCount(),
                run.removedCount(),
                run.failedCount(),
                run.message(),
                run.startedAt(),
                run.finishedAt()
        );
    }

    static GitImportPlanResponse toPlanResponse(GitImportPlan plan) {
        return new GitImportPlanResponse(
                plan.sourceId(),
                plan.ref(),
                plan.commitSha(),
                plan.subpath(),
                plan.plannedAt(),
                plan.count(GitImportPlanItem.ChangeType.ADDED),
                plan.count(GitImportPlanItem.ChangeType.CHANGED),
                plan.count(GitImportPlanItem.ChangeType.UNCHANGED),
                plan.count(GitImportPlanItem.ChangeType.REMOVED),
                plan.items().stream()
                        .map(item -> new GitImportPlanResponse.Item(
                                item.itemPath(),
                                item.skillName(),
                                item.treeSha(),
                                item.changeType().name(),
                                item.previousTreeSha(),
                                item.skillId(),
                                item.fileCount(),
                                item.skippedFiles().stream()
                                        .map(skipped -> new GitImportPlanResponse.SkippedFile(
                                                skipped.path(), skipped.reason()))
                                        .toList()))
                        .toList()
        );
    }

    private record ValidatedSource(String repoUrl,
                                   String repoPath,
                                   String defaultRef,
                                   String subpath,
                                   String namespaceSlug,
                                   String credential,
                                   boolean credentialProvided,
                                   int syncIntervalMinutes) {
    }
}
