package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.SlugValidator;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import com.iflytek.skillhub.gitsync.git.GitMirrorStore;
import com.iflytek.skillhub.gitsync.git.GitRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitRepositoryRef;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
import com.iflytek.skillhub.gitsync.scan.DiscoveredSkill;
import com.iflytek.skillhub.gitsync.scan.GitSourceScan;
import com.iflytek.skillhub.gitsync.select.GitFileSelection;
import com.iflytek.skillhub.gitsync.select.GitSkillFilePolicy;
import com.iflytek.skillhub.gitsync.select.SkippedGitFile;
import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import com.iflytek.skillhub.gitsync.store.GitSyncRunRecord;
import com.iflytek.skillhub.gitsync.store.SkillSourceLinkRecord;
import com.iflytek.skillhub.domain.shared.exception.LocalizedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Imports discovered skill directories into the registry.
 *
 * <p>Runs in process: a source is scanned once, changed directories are
 * downloaded, and each one is published through the same
 * {@link SkillPublishService} path as an uploaded package. Failures are
 * isolated per directory — one broken skill never blocks the rest of the
 * repository — and every run is recorded in {@code git_sync_run}.
 *
 * <p>The publisher is the source's creator, recorded when the source was
 * registered. No privilege is assumed: the publisher must be a member of the
 * target namespace, otherwise the directory is reported as failed. Every
 * imported version lands in review.
 */
@Service
public class GitSyncExecutor {

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_PARTIAL = "PARTIAL";
    static final String STATUS_FAILED = "FAILED";

    /**
     * A run that was refused because the same source was already syncing. Kept in
     * the history so an operator can see why nothing happened.
     */
    public static final String STATUS_SKIPPED = "SKIPPED";
    static final String ALREADY_RUNNING_MESSAGE = "another sync of this source is already running";

    private static final Logger log = LoggerFactory.getLogger(GitSyncExecutor.class);
    private static final int MAX_MESSAGE_LENGTH = 1900;

    private final GitSourceStore store;
    private final GitSourcePlanService planService;
    private final GitRepositoryFetcher fetcher;
    private final SkillPublishService publishService;
    private final NamespaceRepository namespaceRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillMetadataParser metadataParser;
    private final GitMirrorStore mirrors;
    private final GitSyncProperties properties;
    private final MessageSource messageSource;
    private final Clock clock;

    /**
     * Serializes syncs per source inside this JVM.
     *
     * <p>A manual sync and the scheduler used to be able to run the same source
     * at the same time: both plans saw the same directories as "added" and the
     * second publish withdrew the first version to {@code DRAFT}, so a skill
     * ended up with two versions where one was expected. The lock is per source,
     * so unrelated sources still sync in parallel.
     *
     * <p>Known limitation: a multi-instance deployment would need a database
     * claim instead (or as well).
     */
    private final ConcurrentHashMap<Long, ReentrantLock> sourceLocks = new ConcurrentHashMap<>();

    public GitSyncExecutor(GitSourceStore store,
                           GitSourcePlanService planService,
                           GitRepositoryFetcher fetcher,
                           SkillPublishService publishService,
                           NamespaceRepository namespaceRepository,
                           SkillRepository skillRepository,
                           SkillVersionRepository skillVersionRepository,
                           SkillMetadataParser metadataParser,
                           GitMirrorStore mirrors,
                           GitSyncProperties properties,
                           MessageSource messageSource,
                           Clock clock) {
        this.store = store;
        this.planService = planService;
        this.fetcher = fetcher;
        this.publishService = publishService;
        this.namespaceRepository = namespaceRepository;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.metadataParser = metadataParser;
        this.mirrors = mirrors;
        this.properties = properties;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    /**
     * Synchronizes one source.
     *
     * @param triggerType {@link #TRIGGER_MANUAL} or {@link #TRIGGER_SCHEDULED}
     * @return the finished run record
     */
    public GitSyncRunRecord sync(Long sourceId, String triggerType) {
        GitSourceRecord source = store.findById(sourceId)
                .orElseThrow(() -> new DomainNotFoundException("error.gitSource.notFound", sourceId));

        ReentrantLock lock = sourceLocks.computeIfAbsent(sourceId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("Git sync of source {} skipped: another sync is already running", sourceId);
            Instant now = Instant.now(clock);
            Long skippedRunId = store.insertRun(new GitSyncRunRecord(null, sourceId, triggerType, STATUS_SKIPPED,
                    null, null, null, null, null, null, ALREADY_RUNNING_MESSAGE, now, now));
            return new GitSyncRunRecord(skippedRunId, sourceId, triggerType, STATUS_SKIPPED, 0, 0, 0, 0, 0, 0,
                    ALREADY_RUNNING_MESSAGE, now, now);
        }

        try {
            return syncLocked(source, triggerType);
        } finally {
            lock.unlock();
        }
    }

    private GitSyncRunRecord syncLocked(GitSourceRecord source, String triggerType) {
        Long sourceId = source.id();
        // A run still marked RUNNING can only come from a process that died.
        store.failStaleRuns(sourceId, "sync was interrupted before it finished");

        Long runId = store.insertRun(new GitSyncRunRecord(null, sourceId, triggerType, STATUS_RUNNING,
                null, null, null, null, null, null, null, Instant.now(clock), null));

        List<String> notes = new ArrayList<>();
        int planned = 0;
        int added = 0;
        int changed = 0;
        int unchanged = 0;
        int removed = 0;
        int failed = 0;
        String status;

        try {
            GitSourceScan scan = planService.scan(source);
            assertMirrorWithinQuota(source, notes);
            GitImportPlan plan = planService.plan(source, scan);
            planned = plan.items().size();
            Map<String, DiscoveredSkill> skills = scan.skills().stream()
                    .collect(Collectors.toMap(DiscoveredSkill::itemPath, skill -> skill));

            for (GitImportPlanItem item : plan.items()) {
                switch (item.changeType()) {
                    case UNCHANGED -> {
                        unchanged++;
                        store.upsertItem(itemRecord(source, item, plan.commitSha(), item.skillId(),
                                GitSourceItemRecord.STATE_IMPORTED, false));
                    }
                    case REMOVED -> {
                        removed++;
                        store.markItemRemoved(source.id(), item.itemPath());
                        notes.add(note("gitsync.note.removedFromRepository", item.itemPath()));
                    }
                    case ADDED, CHANGED -> {
                        try {
                            importItem(source, plan, item, skills.get(item.itemPath()), notes);
                            if (item.changeType() == GitImportPlanItem.ChangeType.ADDED) {
                                added++;
                            } else {
                                changed++;
                            }
                        } catch (RuntimeException ex) {
                            failed++;
                            store.upsertItem(itemRecord(source, item, plan.commitSha(), item.skillId(),
                                    GitSourceItemRecord.STATE_ERROR, false));
                            notes.add(note("gitsync.note.itemFailed", item.itemPath(), describe(ex)));
                            log.warn("Git sync of source {} could not import {}: {}", sourceId, item.itemPath(),
                                    rootMessage(ex));
                        }
                    }
                }
            }
            // A directory that keeps failing (for example because the registry
            // rejects its content) must not label a source that is otherwise fully
            // in sync as FAILED: the source is in sync, one directory is not.
            status = failed == 0
                    ? STATUS_SUCCESS
                    : (added + changed + unchanged > 0 ? STATUS_PARTIAL : STATUS_FAILED);
        } catch (RuntimeException ex) {
            status = STATUS_FAILED;
            notes.add(describe(ex));
            log.warn("Git sync of source {} failed before importing: {}", sourceId, rootMessage(ex));
        }

        String message = summarize(notes);
        GitSyncRunRecord finished = new GitSyncRunRecord(runId, sourceId, triggerType, status, planned,
                added, changed, unchanged, removed, failed, message, Instant.now(clock), Instant.now(clock));
        store.finishRun(finished);
        store.markSourceSynced(sourceId, status, message);
        return finished;
    }

    private void importItem(GitSourceRecord source,
                            GitImportPlan plan,
                            GitImportPlanItem item,
                            DiscoveredSkill skill,
                            List<String> notes) {
        if (skill == null) {
            throw new IllegalStateException("directory " + item.itemPath() + " disappeared between scan and import");
        }

        GitRepositoryRef repository = planService.repository(source);

        GitFileSelection selection = GitSkillFilePolicy.select(skill.files(), skill.itemPath());
        GitSkillFilePolicy.assertWithinLimits(selection);

        int skippedByPolicy = selection.skipped().size();
        int skippedByContent = 0;
        List<PackageEntry> entries = new ArrayList<>();
        for (var file : selection.selected()) {
            byte[] content = fetcher.readBlob(repository, file.entry().sha());
            String problem = GitSkillFilePolicy.contentProblem(file.packagePath(), content);
            if (problem != null) {
                skippedByContent++;
                continue;
            }
            entries.add(new PackageEntry(file.packagePath(), content, content.length, contentType(file.packagePath())));
        }
        if (skippedByPolicy + skippedByContent > 0) {
            notes.add(note("gitsync.note.skippedFiles", item.itemPath(),
                    skippedByPolicy + skippedByContent));
        }

        Namespace namespace = namespaceRepository.findBySlug(source.namespaceSlug())
                .orElseThrow(() -> new DomainNotFoundException("error.namespace.slug.notFound", source.namespaceSlug()));

        byte[] skillDocument = entries.stream()
                .filter(entry -> SkillPackagePolicy.SKILL_MD_PATH.equals(entry.path()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SKILL.md missing after file selection"))
                .content();

        String derivedVersion = versionToPublish(source, namespace, plan.commitSha(), skillDocument);
        if (derivedVersion != null) {
            byte[] adjusted = GitSkillDocumentVersion.withVersion(skillDocument, derivedVersion);
            replaceEntry(entries, adjusted);
            notes.add(note("gitsync.note.versionAdjusted", item.itemPath(), derivedVersion));
        }

        SkillPublishService.PublishResult result = publishService.publishFromEntries(
                source.namespaceSlug(),
                entries,
                source.publisherId(),
                source.defaultVisibility(),
                // No platform role is granted: imported versions await review like
                // any other publish. Skipping review is not a source setting.
                Set.of()
        );

        Instant now = Instant.now(clock);
        store.upsertItem(new GitSourceItemRecord(null, source.id(), item.itemPath(), item.skillName(),
                item.treeSha(), plan.commitSha(), result.skillId(), GitSourceItemRecord.STATE_IMPORTED, now, now));
        store.upsertLink(new SkillSourceLinkRecord(null, result.skillId(), source.id(),
                source.repoUrl(), item.itemPath(), plan.ref(), plan.commitSha(), item.treeSha(), now));
    }

    /**
     * Refuses to import from a mirror that outgrew its quota, and drops the mirror
     * so the disk does not stay over the limit. The source is not broken: it fails
     * again on the next run, after the operator raises the quota or the repository
     * shrinks.
     */
    private void assertMirrorWithinQuota(GitSourceRecord source, List<String> notes) {
        long maxBytes = properties.getLimits().getMaxMirrorBytes();
        if (maxBytes <= 0) {
            return;
        }
        long size = mirrors.size(source.id());
        if (size <= maxBytes) {
            return;
        }
        mirrors.delete(source.id());
        throw new IllegalStateException(note("gitsync.note.mirrorTooLarge", humanSize(size), humanSize(maxBytes)));
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + " MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " B";
    }

    /**
     * Keeps the declared version while it is still publishable; otherwise returns
     * a deterministic per-commit version so the sync can proceed.
     *
     * @return the version to rewrite into the document, or {@code null} to keep it
     */
    private String versionToPublish(GitSourceRecord source, Namespace namespace, String commitSha, byte[] skillDocument) {
        String document = new String(skillDocument, StandardCharsets.UTF_8);
        String declared = GitSkillDocumentVersion.readVersion(document);
        if (declared == null) {
            return null;
        }
        String slug;
        try {
            slug = SlugValidator.slugify(metadataParser.parse(document).name());
        } catch (RuntimeException ex) {
            return null;
        }
        Optional<Skill> existing = skillRepository.findByNamespaceIdAndSlugAndOwnerId(namespace.getId(), slug,
                source.publisherId());
        if (existing.isEmpty()) {
            return null;
        }
        Optional<SkillVersion> published = skillVersionRepository
                .findBySkillIdAndVersion(existing.get().getId(), declared)
                .filter(version -> version.getStatus() == SkillVersionStatus.PUBLISHED);
        if (published.isEmpty()) {
            return null;
        }
        return GitSkillDocumentVersion.derivedVersion(declared, commitSha);
    }

    private static void replaceEntry(List<PackageEntry> entries, byte[] skillDocument) {
        for (int index = 0; index < entries.size(); index++) {
            PackageEntry entry = entries.get(index);
            if (SkillPackagePolicy.SKILL_MD_PATH.equals(entry.path())) {
                entries.set(index, new PackageEntry(entry.path(), skillDocument, skillDocument.length,
                        entry.contentType()));
                return;
            }
        }
    }

    /**
     * Mirrors the mapping used by the upload path
     * ({@code MultipartPackageExtractor#determineContentType}) so a skill looks
     * the same whether it arrived as an archive or from a repository. Kept local
     * to avoid touching the upstream extractor.
     */
    private static String contentType(String path) {
        if (path.endsWith(".py")) {
            return "text/x-python";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        if (path.endsWith(".yaml") || path.endsWith(".yml")) {
            return "application/x-yaml";
        }
        if (path.endsWith(".txt")) {
            return "text/plain";
        }
        if (path.endsWith(".md")) {
            return "text/markdown";
        }
        return "application/octet-stream";
    }

    private GitSourceItemRecord itemRecord(GitSourceRecord source,
                                           GitImportPlanItem item,
                                           String commitSha,
                                           Long skillId,
                                           String state,
                                           boolean synced) {
        Instant now = Instant.now(clock);
        return new GitSourceItemRecord(null, source.id(), item.itemPath(), item.skillName(), item.treeSha(),
                commitSha, skillId, state, now, synced ? now : null);
    }

    private static String summarize(List<String> notes) {
        if (notes.isEmpty()) {
            return null;
        }
        String joined = String.join("; ", notes);
        return joined.length() <= MAX_MESSAGE_LENGTH ? joined : joined.substring(0, MAX_MESSAGE_LENGTH);
    }

    /**
     * Renders a failure for the run history.
     *
     * Domain exceptions carry a message <em>code</em> rather than prose, so
     * resolving it here keeps the raw code (for example
     * {@code error.skill.publish.precheck.failed}) out of the administrator's
     * view. Anything else keeps its own message.
     */
    private String describe(Throwable throwable) {
        Throwable current = rootCause(throwable);
        if (current instanceof LocalizedMessage localized) {
            return messageSource.getMessage(localized.messageCode(), localized.messageArgs(),
                    localized.messageCode(), LocaleContextHolder.getLocale());
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /** Resolves one git-sync note in the administrator's language. */
    private String note(String code, Object... args) {
        return messageSource.getMessage(code, args, code, LocaleContextHolder.getLocale());
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = rootCause(throwable);
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
