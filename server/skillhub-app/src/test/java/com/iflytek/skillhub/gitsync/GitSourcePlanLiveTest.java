package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.gitsync.git.GitCliRepositoryFetcher;
import com.iflytek.skillhub.gitsync.git.GitCommandRunner;
import com.iflytek.skillhub.gitsync.git.GitCommitRef;
import com.iflytek.skillhub.gitsync.git.GitCredential;
import com.iflytek.skillhub.gitsync.git.GitMirrorStore;
import com.iflytek.skillhub.gitsync.git.GitRepositoryRef;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import com.iflytek.skillhub.gitsync.plan.GitImportPlan;
import com.iflytek.skillhub.gitsync.plan.GitImportPlanItem;
import com.iflytek.skillhub.gitsync.scan.DiscoveredSkill;
import com.iflytek.skillhub.gitsync.scan.GitSkillDiscovery;
import com.iflytek.skillhub.gitsync.select.GitFileSelection;
import com.iflytek.skillhub.gitsync.select.GitSkillFilePolicy;
import com.iflytek.skillhub.gitsync.select.GitTreeFile;
import com.iflytek.skillhub.gitsync.store.GitSourceItemRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import com.iflytek.skillhub.gitsync.store.GitSourceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live end-to-end check of discovery, metadata reading, and change detection
 * against a real public repository, fetched with the local git binary.
 *
 * <p>Opt-in because it needs network access: set
 * {@code SKILLHUB_LIVE_GIT_TESTS=true} and optionally
 * {@code SKILLHUB_LIVE_GIT_TOKEN} (a token is only needed for private
 * repositories; public ones clone anonymously over HTTPS).
 * Optionally override {@code SKILLHUB_LIVE_GIT_REPO},
 * {@code SKILLHUB_LIVE_GIT_SUBPATH} and {@code SKILLHUB_LIVE_GIT_REF}.
 */
@EnabledIfEnvironmentVariable(named = "SKILLHUB_LIVE_GIT_TESTS", matches = "true")
class GitSourcePlanLiveTest {

    private static final String REPO = System.getenv().getOrDefault("SKILLHUB_LIVE_GIT_REPO", "anthropics/skills");
    private static final String SUBPATH = System.getenv().getOrDefault("SKILLHUB_LIVE_GIT_SUBPATH", "skills");
    private static final String REF = System.getenv().getOrDefault("SKILLHUB_LIVE_GIT_REF", "main");
    private static final String TOKEN = System.getenv().getOrDefault("SKILLHUB_LIVE_GIT_TOKEN", "");
    private static final String REPO_URL = "https://github.com/" + REPO;

    /** One mirror for the whole class: cloning the same repository twice wastes minutes. */
    private static final GitMirrorStore MIRRORS = liveMirrors();

    private static GitMirrorStore liveMirrors() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.setWorkDir(Path.of(System.getProperty("java.io.tmpdir"), "skillhub-live-git-mirrors"));
        return new GitMirrorStore(new GitCommandRunner(properties), properties);
    }

    private GitMirrorStore mirrors() {
        return MIRRORS;
    }

    private GitRepositoryRef repository(long sourceId) {
        return new GitRepositoryRef(sourceId, REPO_URL, REPO,
                TOKEN.isBlank() ? GitCredential.anonymous() : GitCredential.token(null, TOKEN));
    }

    @Test
    void plansRealRepositoryAndDetectsUnchangedDirectories() {
        GitSourceStore store = mock(GitSourceStore.class);
        GitCredentialResolver credentials = mock(GitCredentialResolver.class);
        when(credentials.resolve(any())).thenReturn(
                TOKEN.isBlank() ? GitCredential.anonymous() : GitCredential.token(null, TOKEN));

        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(mirrors());
        GitSourcePlanService service = new GitSourcePlanService(store, fetcher, credentials,
                new SkillMetadataParser(), liveHostPolicy(), mock(GitPlanPrecheck.class), Clock.systemUTC());

        GitSourceRecord source = new GitSourceRecord(1L, REPO_URL, REPO, REF, SUBPATH, "global",
                TOKEN.isBlank() ? null : "live", "live", 60, true,
                com.iflytek.skillhub.domain.skill.SkillVisibility.PUBLIC,
                "live", Instant.now(), Instant.now(), null, null, null, null);

        when(store.findItems(1L)).thenReturn(List.of());
        GitImportPlan first = service.plan(source);

        assertFalse(first.items().isEmpty(), "expected to discover skill directories in " + REPO + "/" + SUBPATH);
        assertEquals(40, first.commitSha().length(), "commit sha should be resolved to a full commit id");
        assertEquals(first.items().size(), first.count(GitImportPlanItem.ChangeType.ADDED));
        for (GitImportPlanItem item : first.items()) {
            assertNotNull(item.treeSha(), "every discovered directory needs a fingerprint");
            assertEquals(64, item.treeSha().length(), "fingerprint is a sha256 hex digest");
            assertTrue(item.itemPath().startsWith(SUBPATH), "item path stays inside the configured subpath: "
                    + item.itemPath());
        }

        GitImportPlanItem sample = first.items().getFirst();
        assertNotNull(sample.skillName());
        assertFalse(sample.skillName().isBlank(), "SKILL.md metadata should yield a display name");

        // Re-plan with the previous fingerprints persisted: the repository is
        // unchanged, so nothing may be reported as added or changed.
        List<GitSourceItemRecord> persisted = new java.util.ArrayList<>();
        for (int index = 0; index < first.items().size(); index++) {
            GitImportPlanItem item = first.items().get(index);
            persisted.add(new GitSourceItemRecord(null, 1L, item.itemPath(), item.skillName(), item.treeSha(),
                    first.commitSha(), 100L + index, GitSourceItemRecord.STATE_IMPORTED, Instant.now(), Instant.now()));
        }
        when(store.findItems(1L)).thenReturn(persisted);

        GitImportPlan second = service.plan(source);

        assertEquals(0, second.count(GitImportPlanItem.ChangeType.ADDED));
        assertEquals(0, second.count(GitImportPlanItem.ChangeType.CHANGED));
        assertEquals(0, second.count(GitImportPlanItem.ChangeType.REMOVED));
        assertEquals(first.items().size(), second.count(GitImportPlanItem.ChangeType.UNCHANGED));

        System.out.println("Live git plan: repo=" + REPO + "/" + SUBPATH
                + " commit=" + first.commitSha()
                + " skills=" + first.items().size()
                + " first=" + sample.itemPath() + " (" + sample.skillName() + ")");
    }

    /**
     * Downloads every file of the first discovered skill and checks that the
     * bytes round-trip, that sizes match the tree, and that the selection and
     * metadata parsing work on real content.
     */
    @Test
    void downloadsRealSkillDirectoryByteForByte() {
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(mirrors());
        GitRepositoryRef repository = repository(1L);

        GitCommitRef commit = fetcher.resolveRef(repository, REF);
        List<GitTreeEntry> tree = fetcher.listTree(repository, commit.commitSha());
        List<DiscoveredSkill> discovered = GitSkillDiscovery.discover(tree, SUBPATH);
        assertFalse(discovered.isEmpty(), "expected at least one skill directory in " + REPO + "/" + SUBPATH);

        DiscoveredSkill skill = discovered.getFirst();
        GitFileSelection selection = GitSkillFilePolicy.select(skill.files(), skill.itemPath());
        GitSkillFilePolicy.assertWithinLimits(selection);

        long downloaded = 0;
        for (GitTreeFile file : selection.selected()) {
            byte[] content = fetcher.readBlob(repository, file.entry().sha());
            assertEquals(file.entry().size(), content.length,
                    "blob size mismatch for " + file.packagePath());
            assertNull(GitSkillFilePolicy.contentProblem(file.packagePath(), content),
                    "content check failed for " + file.packagePath());
            downloaded += content.length;
        }

        byte[] skillDocument = fetcher.readBlob(repository, selection.selected().stream()
                .filter(file -> "SKILL.md".equals(file.packagePath()))
                .findFirst()
                .orElseThrow()
                .entry()
                .sha());
        String name = new SkillMetadataParser().parse(new String(skillDocument, StandardCharsets.UTF_8)).name();

        System.out.println("Live git download: " + skill.itemPath()
                + " files=" + selection.selected().size()
                + " skipped=" + selection.skipped().size()
                + " bytes=" + downloaded
                + " name=" + name);
        assertFalse(name.isBlank());
    }

    /** The live test fetches the real public repository it names. */
    private static GitSyncUrlPolicy liveHostPolicy() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.getUrl().setAllowedHosts(List.of("github.com"));
        return new GitSyncUrlPolicy(properties);
    }
}
