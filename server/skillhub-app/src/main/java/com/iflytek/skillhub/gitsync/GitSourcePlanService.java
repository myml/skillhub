package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.domain.namespace.SlugValidator;
import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import com.iflytek.skillhub.gitsync.git.GitCommitRef;
import com.iflytek.skillhub.gitsync.git.GitRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitRepositoryRef;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
import com.iflytek.skillhub.gitsync.plan.PlanProblem;
import com.iflytek.skillhub.gitsync.scan.DiscoveredSkill;
import com.iflytek.skillhub.gitsync.scan.GitSkillDiscovery;
import com.iflytek.skillhub.gitsync.scan.GitSourceScan;
import com.iflytek.skillhub.gitsync.select.GitFileSelection;
import com.iflytek.skillhub.gitsync.select.GitSkillFilePolicy;
import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds an import plan for a source without touching the registry.
 *
 * <p>Change detection compares the per-directory fingerprint computed by
 * {@link GitSkillDiscovery} against the fingerprint stored for the previous
 * sync, so unrelated commits do not mark a skill as changed.
 *
 * <p>The dry-run additionally predicts which directories a sync would fail to
 * import (see {@link GitPlanPrecheck}); scheduling runs skip that prediction,
 * since they would otherwise repeat the same registry lookups every few
 * minutes for a result nobody reads.
 */
@Service
public class GitSourcePlanService {

    private static final Logger log = LoggerFactory.getLogger(GitSourcePlanService.class);

    private final GitSourceStore store;
    private final GitRepositoryFetcher fetcher;
    private final GitCredentialResolver credentialResolver;
    private final SkillMetadataParser metadataParser;
    private final GitSyncUrlPolicy urlPolicy;
    private final GitPlanPrecheck precheck;
    private final Clock clock;

    public GitSourcePlanService(GitSourceStore store,
                                GitRepositoryFetcher fetcher,
                                GitCredentialResolver credentialResolver,
                                SkillMetadataParser metadataParser,
                                GitSyncUrlPolicy urlPolicy,
                                GitPlanPrecheck precheck,
                                Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.credentialResolver = credentialResolver;
        this.metadataParser = metadataParser;
        this.urlPolicy = urlPolicy;
        this.precheck = precheck;
        this.clock = clock;
    }

    /**
     * Resolves the source's ref, lists its tree, and loads the previously
     * observed items. Read-only.
     */
    public GitSourceScan scan(GitSourceRecord source) {
        GitRepositoryRef repository = repository(source);

        GitCommitRef commit = fetcher.resolveRef(repository, source.defaultRef());
        List<GitTreeEntry> tree = fetcher.listTree(repository, commit.commitSha());
        List<DiscoveredSkill> discovered = GitSkillDiscovery.discover(tree, source.subpath());

        Map<String, GitSourceItemRecord> previousItems = new LinkedHashMap<>();
        for (GitSourceItemRecord item : store.findItems(source.id())) {
            previousItems.put(item.itemPath(), item);
        }

        return new GitSourceScan(source.id(), commit.ref(), commit.commitSha(), source.subpath(),
                List.copyOf(discovered), Map.copyOf(previousItems));
    }

    /**
     * Dry-run used by the management API: includes the predicted-failure list.
     */
    public GitImportPlan plan(GitSourceRecord source) {
        return plan(source, scan(source), true);
    }

    /**
     * Builds the plan for an already completed scan.
     *
     * <p>Used by the executor, which does not need the predictions: it publishes
     * anyway, and the publish itself reports any real failure.
     */
    public GitImportPlan plan(GitSourceRecord source, GitSourceScan scan) {
        return plan(source, scan, false);
    }

    private GitImportPlan plan(GitSourceRecord source, GitSourceScan scan, boolean withPrechecks) {
        GitRepositoryRef repository = repository(source);

        List<GitImportPlanItem> items = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (DiscoveredSkill skill : scan.skills()) {
            seenPaths.add(skill.itemPath());
            GitSourceItemRecord previous = scan.previousItems().get(skill.itemPath());
            GitFileSelection selection = GitSkillFilePolicy.select(skill.files(), skill.itemPath());
            PlanDocument document = readSkillDocument(fetcher, repository, scan.commitSha(), skill);
            items.add(new GitImportPlanItem(
                    skill.itemPath(),
                    displayName(document, skill),
                    skill.treeSha(),
                    classify(previous, skill),
                    previous == null ? null : previous.treeSha(),
                    previous == null ? null : previous.skillId(),
                    selection.selected().size(),
                    selection.skipped(),
                    withPrechecks ? problemsFor(source, document, selection) : List.of()
            ));
        }

        for (GitSourceItemRecord previous : scan.previousItems().values()) {
            if (!seenPaths.contains(previous.itemPath()) && !"REMOVED".equals(previous.state())) {
                items.add(new GitImportPlanItem(
                        previous.itemPath(),
                        previous.skillName(),
                        previous.treeSha(),
                        GitImportPlanItem.ChangeType.REMOVED,
                        previous.treeSha(),
                        previous.skillId(),
                        0,
                        List.of(),
                        List.of()
                ));
            }
        }

        items.sort(Comparator.comparing(GitImportPlanItem::itemPath));
        return new GitImportPlan(source.id(), scan.ref(), scan.commitSha(), source.subpath(),
                List.copyOf(items), Instant.now(clock));
    }

    GitRepositoryRef repository(GitSourceRecord source) {
        // The single gate before any network access: a source whose host is no
        // longer allowed fails the run instead of quietly fetching.
        urlPolicy.requireAllowed(source.repoUrl());
        return new GitRepositoryRef(
                source.id(),
                source.repoUrl(),
                source.repoPath(),
                credentialResolver.resolve(source)
        );
    }

    private static GitImportPlanItem.ChangeType classify(GitSourceItemRecord previous, DiscoveredSkill skill) {
        if (previous == null) {
            return GitImportPlanItem.ChangeType.ADDED;
        }
        // A directory that failed to import — or was never associated with a skill —
        // is retried even though its fingerprint has not moved.
        if (GitSourceItemRecord.STATE_ERROR.equals(previous.state()) || previous.skillId() == null) {
            return GitImportPlanItem.ChangeType.CHANGED;
        }
        return previous.treeSha().equals(skill.treeSha())
                ? GitImportPlanItem.ChangeType.UNCHANGED
                : GitImportPlanItem.ChangeType.CHANGED;
    }

    /**
     * Registry-side and package-side reasons the directory would not import.
     *
     * <p>A directory whose document cannot be parsed reports that instead of the
     * name checks: without a name there is no slug to compare.
     */
    private List<PlanProblem> problemsFor(GitSourceRecord source, PlanDocument document, GitFileSelection selection) {
        List<PlanProblem> problems = new ArrayList<>(limitProblems(selection));
        if (document.problem() != null) {
            problems.add(document.problem());
        } else if (document.metadata() != null) {
            problems.addAll(precheck.check(source, SlugValidator.slugify(document.metadata().name())));
        }
        return List.copyOf(problems);
    }

    /**
     * The registry's hard package limits, which a sync cannot work around: too
     * many files, a package that is too large, or no {@code SKILL.md} at the
     * directory root.
     */
    static List<PlanProblem> limitProblems(GitFileSelection selection) {
        List<PlanProblem> problems = new ArrayList<>();
        int fileCount = selection.selected().size();
        if (fileCount > SkillPackagePolicy.MAX_FILE_COUNT) {
            problems.add(PlanProblem.of("gitsync.plan.problem.tooManyFiles",
                    fileCount, SkillPackagePolicy.MAX_FILE_COUNT));
        }
        long totalBytes = selection.selected().stream()
                .mapToLong(file -> file.entry().size())
                .sum();
        if (totalBytes > SkillPackagePolicy.MAX_TOTAL_PACKAGE_SIZE) {
            problems.add(PlanProblem.of("gitsync.plan.problem.packageTooLarge",
                    megabytes(totalBytes), megabytes(SkillPackagePolicy.MAX_TOTAL_PACKAGE_SIZE)));
        }
        if (selection.selected().stream()
                .noneMatch(file -> SkillPackagePolicy.SKILL_MD_PATH.equals(file.packagePath()))) {
            problems.add(PlanProblem.of("gitsync.plan.problem.missingSkillMd"));
        }
        return problems;
    }

    private static long megabytes(long bytes) {
        return Math.max(1L, bytes / (1024L * 1024L));
    }

    private String displayName(PlanDocument document, DiscoveredSkill skill) {
        if (document.metadata() != null && document.metadata().name() != null
                && !document.metadata().name().isBlank()) {
            return document.metadata().name();
        }
        return fallbackName(skill.itemPath());
    }

    /**
     * Reads and parses {@code SKILL.md} once, keeping the failure instead of
     * swallowing it: the same document decides the displayed name and whether
     * the directory can be imported at all.
     */
    private PlanDocument readSkillDocument(GitRepositoryFetcher fetcher,
                                           GitRepositoryRef repository,
                                           String commitSha,
                                           DiscoveredSkill skill) {
        String documentPath = skill.itemPath().isEmpty()
                ? GitSkillDiscovery.SKILL_DOCUMENT
                : skill.itemPath() + "/" + GitSkillDiscovery.SKILL_DOCUMENT;
        try {
            return new PlanDocument(metadataParser.parse(fetcher.readFile(repository, commitSha, documentPath)), null);
        } catch (LocalizedDomainException ex) {
            return new PlanDocument(null, new PlanProblem(ex.messageCode(), List.of(ex.messageArgs())));
        } catch (RuntimeException ex) {
            log.debug("Could not read skill metadata from {}: {}", documentPath, ex.getMessage());
            return new PlanDocument(null, PlanProblem.of("gitsync.plan.problem.skillDocumentUnreadable",
                    String.valueOf(ex.getMessage())));
        }
    }

    private static String fallbackName(String itemPath) {
        if (itemPath == null || itemPath.isEmpty()) {
            return "root";
        }
        int slash = itemPath.lastIndexOf('/');
        return slash < 0 ? itemPath : itemPath.substring(slash + 1);
    }

    /**
     * Result of reading one {@code SKILL.md}: either parsed metadata or the
     * reason it could not be used.
     */
    private record PlanDocument(SkillMetadata metadata, PlanProblem problem) {
    }
}