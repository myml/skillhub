package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.gitsync.git.GitCommitRef;
import com.iflytek.skillhub.gitsync.git.GitRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitRepositoryRef;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
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
 */
@Service
public class GitSourcePlanService {

    private static final Logger log = LoggerFactory.getLogger(GitSourcePlanService.class);

    private final GitSourceStore store;
    private final GitRepositoryFetcher fetcher;
    private final GitCredentialResolver credentialResolver;
    private final SkillMetadataParser metadataParser;
    private final GitSyncUrlPolicy urlPolicy;
    private final Clock clock;

    public GitSourcePlanService(GitSourceStore store,
                                GitRepositoryFetcher fetcher,
                                GitCredentialResolver credentialResolver,
                                SkillMetadataParser metadataParser,
                                GitSyncUrlPolicy urlPolicy,
                                Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.credentialResolver = credentialResolver;
        this.metadataParser = metadataParser;
        this.urlPolicy = urlPolicy;
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

    public GitImportPlan plan(GitSourceRecord source) {
        return plan(source, scan(source));
    }

    /**
     * Builds the plan for an already completed scan.
     */
    public GitImportPlan plan(GitSourceRecord source, GitSourceScan scan) {
        GitRepositoryRef repository = repository(source);

        List<GitImportPlanItem> items = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (DiscoveredSkill skill : scan.skills()) {
            seenPaths.add(skill.itemPath());
            GitSourceItemRecord previous = scan.previousItems().get(skill.itemPath());
            GitFileSelection selection = GitSkillFilePolicy.select(skill.files(), skill.itemPath());
            items.add(new GitImportPlanItem(
                    skill.itemPath(),
                    resolveSkillName(fetcher, repository, scan.commitSha(), skill),
                    skill.treeSha(),
                    classify(previous, skill),
                    previous == null ? null : previous.treeSha(),
                    previous == null ? null : previous.skillId(),
                    selection.selected().size(),
                    selection.skipped()
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
     * Reads the skill name from {@code SKILL.md}; a broken document only degrades
     * the display name, it does not fail the plan.
     */
    private String resolveSkillName(GitRepositoryFetcher fetcher,
                                    GitRepositoryRef repository,
                                    String commitSha,
                                    DiscoveredSkill skill) {
        String documentPath = skill.itemPath().isEmpty()
                ? GitSkillDiscovery.SKILL_DOCUMENT
                : skill.itemPath() + "/" + GitSkillDiscovery.SKILL_DOCUMENT;
        try {
            SkillMetadata metadata = metadataParser.parse(fetcher.readFile(repository, commitSha, documentPath));
            if (metadata.name() != null && !metadata.name().isBlank()) {
                return metadata.name();
            }
        } catch (RuntimeException ex) {
            log.debug("Could not read skill metadata from {}: {}", documentPath, ex.getMessage());
        }
        return fallbackName(skill.itemPath());
    }

    private static String fallbackName(String itemPath) {
        if (itemPath == null || itemPath.isEmpty()) {
            return "root";
        }
        int slash = itemPath.lastIndexOf('/');
        return slash < 0 ? itemPath : itemPath.substring(slash + 1);
    }
}
