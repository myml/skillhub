package com.iflytek.skillhub.gitsync.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.gitsync.GitSyncProperties;
import com.iflytek.skillhub.gitsync.scan.GitSkillDiscovery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the git-based fetcher against real repositories created on disk.
 *
 * <p>This is the proof that route B needs no provider API: the same code path
 * serves any server that speaks git, and here the "server" is a plain repository
 * reached over {@code file://} — including one that is not GitHub, GitLab or
 * Gitea at all.
 */
class GitCliRepositoryFetcherTest {

    private static final String SKILL_DOCUMENT = """
            ---
            name: Disk Helper
            description: Helper stored on disk
            ---
            # Disk Helper
            """;

    @BeforeAll
    static void requireGit() {
        Assumptions.assumeTrue(ProcessBuilder.class != null);
    }

    @TempDir
    Path tempDir;

    private GitSyncProperties properties() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.setWorkDir(tempDir.resolve("mirrors"));
        return properties;
    }

    private GitCommandRunner runner(GitSyncProperties properties) {
        return new GitCommandRunner(properties);
    }

    /** Creates a real repository with two skills plus a binary file and a nested path. */
    private Path createRepository() throws IOException {
        return createRepository("main");
    }

    /** The same repository, with a chosen default branch. */
    private Path createRepository(String defaultBranch) throws IOException {
        Path repo = tempDir.resolve("origin");
        Files.createDirectories(repo);
        GitSyncProperties local = properties();
        GitCommandRunner git = runner(local);
        git.run(repo, List.of("init", "--quiet", "-b", defaultBranch, "."), Map.of());
        git.run(repo, List.of("config", "user.email", "test@example.com"), Map.of());
        git.run(repo, List.of("config", "user.name", "Test"), Map.of());

        write(repo, "skills/pdf/SKILL.md", SKILL_DOCUMENT);
        write(repo, "skills/pdf/reference.md", "# Reference\n");
        write(repo, "skills/excel/SKILL.md", SKILL_DOCUMENT.replace("Disk Helper", "Excel Helper"));
        write(repo, "skills/with space/SKILL.md", SKILL_DOCUMENT.replace("Disk Helper", "Spaced Helper"));
        Files.createDirectories(repo.resolve("skills/pdf/fonts"));
        Files.write(repo.resolve("skills/pdf/fonts/font.bin"), new byte[] {0, 1, 2, (byte) 0xFF, 0, 42});
        write(repo, "README.md", "# Not a skill\n");

        git.run(repo, List.of("add", "-A"), Map.of());
        git.run(repo, List.of("commit", "--quiet", "-m", "initial skills"), Map.of());
        return repo;
    }

    private static void write(Path repo, String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private GitRepositoryRef reference(Path repo, long sourceId) {
        return new GitRepositoryRef(sourceId, repo.toUri().toString(),
                GitRepoUrls.repoPath(repo.toUri().toString()), GitCredential.anonymous());
    }

    @Test
    void readsTreeAndBlobsFromAnyRepositoryWithoutAnApi() throws IOException {
        GitSyncProperties properties = properties();
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(
                new GitMirrorStore(runner(properties), properties));
        Path repo = createRepository();
        GitRepositoryRef reference = reference(repo, 1L);

        GitCommitRef commit = fetcher.resolveRef(reference, "main");
        assertEquals(40, commit.commitSha().length());
        assertEquals("main", commit.ref());

        List<GitTreeEntry> tree = fetcher.listTree(reference, commit.commitSha());
        assertTrue(tree.stream().anyMatch(entry -> entry.path().equals("skills/pdf/SKILL.md") && entry.isBlob()));
        assertTrue(tree.stream().anyMatch(entry -> entry.path().equals("skills/with space/SKILL.md")));
        assertTrue(tree.stream().noneMatch(GitTreeEntry::isTree),
                "the listing is fully recursive, so no directory entry remains");

        String document = fetcher.readFile(reference, commit.commitSha(), "skills/pdf/SKILL.md");
        assertEquals(SKILL_DOCUMENT, document);

        GitTreeEntry font = tree.stream()
                .filter(entry -> entry.path().equals("skills/pdf/fonts/font.bin"))
                .findFirst()
                .orElseThrow();
        byte[] blob = fetcher.readBlob(reference, font.sha());
        assertEquals(font.size(), blob.length, "tree sizes must match the blob length");
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 0xFF, 0, 42}, blob, "binary content round-trips");

        List<com.iflytek.skillhub.gitsync.scan.DiscoveredSkill> discovered =
                GitSkillDiscovery.discover(tree, "skills");
        assertEquals(3, discovered.size(), "the three committed skill directories are discovered");
        assertEquals(List.of("skills/excel", "skills/pdf", "skills/with space"),
                discovered.stream()
                        .map(com.iflytek.skillhub.gitsync.scan.DiscoveredSkill::itemPath)
                        .sorted()
                        .toList());
    }

    @Test
    void detectsChangedDirectoryFingerprintsAfterANewCommit() throws IOException {
        GitSyncProperties properties = properties();
        GitCommandRunner git = runner(properties);
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(new GitMirrorStore(git, properties));
        Path repo = createRepository();
        GitRepositoryRef reference = reference(repo, 2L);

        GitCommitRef first = fetcher.resolveRef(reference, "main");
        List<com.iflytek.skillhub.gitsync.scan.DiscoveredSkill> before =
                GitSkillDiscovery.discover(fetcher.listTree(reference, first.commitSha()), "skills");
        String pdfBefore = fingerprint(before, "skills/pdf");
        String excelBefore = fingerprint(before, "skills/excel");

        write(repo, "skills/pdf/reference.md", "# Reference v2\n");
        git.run(repo, List.of("add", "-A"), Map.of());
        git.run(repo, List.of("commit", "--quiet", "-m", "update pdf reference"), Map.of());

        GitCommitRef second = fetcher.resolveRef(reference, "main");
        assertNotEquals(first.commitSha(), second.commitSha(), "a new commit must be fetched");
        List<com.iflytek.skillhub.gitsync.scan.DiscoveredSkill> after =
                GitSkillDiscovery.discover(fetcher.listTree(reference, second.commitSha()), "skills");

        assertNotEquals(pdfBefore, fingerprint(after, "skills/pdf"), "the touched directory changes");
        assertEquals(excelBefore, fingerprint(after, "skills/excel"), "untouched directories keep their fingerprint");
    }

    @Test
    void resolvesTagsAndDoesNotRefetchAnUnchangedRepository() throws IOException {
        GitSyncProperties properties = properties();
        RecordingGitCommandRunner git = new RecordingGitCommandRunner(properties);
        GitMirrorStore mirrors = new GitMirrorStore(git, properties);
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(mirrors);
        Path repo = createRepository();
        git.run(repo, List.of("tag", "v1.0.0"), Map.of());

        GitRepositoryRef reference = reference(repo, 3L);
        GitCommitRef byBranch = fetcher.resolveRef(reference, "main");
        GitCommitRef byTag = fetcher.resolveRef(reference, "v1.0.0");
        assertEquals(byBranch.commitSha(), byTag.commitSha(), "a tag resolves to the commit it points at");

        Path mirror = mirrors.mirrorPath(3L);
        assertTrue(mirrors.hasCommit(mirror, byBranch.commitSha()));
        assertTrue(mirrors.lastFetch(3L).isPresent());

        // An unchanged repository costs one ls-remote round trip and no download.
        git.clear();
        assertEquals(byBranch.commitSha(), fetcher.resolveRef(reference, "main").commitSha());
        assertTrue(git.subcommands().contains("ls-remote"), git.subcommands().toString());
        assertFalse(git.subcommands().contains("fetch"), "an unchanged commit must not be fetched again: "
                + git.subcommands());
    }

    @Test
    void servesTreeAndBlobReadsFromTheMirrorWithoutTheRemote() throws IOException {
        GitSyncProperties properties = properties();
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(
                new GitMirrorStore(runner(properties), properties));
        Path repo = createRepository();
        GitRepositoryRef reference = reference(repo, 6L);

        GitCommitRef commit = fetcher.resolveRef(reference, "main");
        List<GitTreeEntry> tree = fetcher.listTree(reference, commit.commitSha());
        String sha = tree.stream()
                .filter(entry -> entry.path().equals("skills/pdf/SKILL.md"))
                .findFirst()
                .orElseThrow()
                .sha();

        // The remote disappears: listing and reading still work, because the sync
        // never talks to the network per file.
        deleteRecursively(repo);
        assertEquals(SKILL_DOCUMENT, fetcher.readFile(reference, commit.commitSha(), "skills/pdf/SKILL.md"));
        assertArrayEquals(SKILL_DOCUMENT.getBytes(StandardCharsets.UTF_8), fetcher.readBlob(reference, sha));
        assertEquals(tree.size(), fetcher.listTree(reference, commit.commitSha()).size());
    }

    @Test
    void reportsAnUnreachableRemoteInsteadOfSyncingStaleContent() throws IOException {
        GitSyncProperties properties = properties();
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(
                new GitMirrorStore(runner(properties), properties));
        Path repo = createRepository();
        GitRepositoryRef reference = reference(repo, 7L);
        fetcher.resolveRef(reference, "main");

        deleteRecursively(repo);
        GitProviderException error = assertThrows(GitProviderException.class,
                () -> fetcher.resolveRef(reference, "main"));
        assertTrue(error.getMessage().contains("ls-remote"), error.getMessage());
    }

    @Test
    void rejectsAnUnknownRefAndListsTheBranchesThatDoExist() throws IOException {
        GitSyncProperties properties = properties();
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(
                new GitMirrorStore(runner(properties), properties));
        Path repo = createRepository("develop");
        GitRepositoryRef reference = reference(repo, 4L);

        // "master" does not exist here: that used to surface as
        // "fatal: couldn't find remote ref master" with a 500, which reads like a
        // server fault even though it is a configuration mistake.
        GitRefNotFoundException error = assertThrows(GitRefNotFoundException.class,
                () -> fetcher.resolveRef(reference, "master"));
        assertEquals("master", error.ref());
        assertTrue(error.availableBranches().contains("develop"),
                "the error names the branches the server does advertise: " + error.availableBranches());
        assertEquals(400, error.statusCode(), "a wrong ref is a caller problem, not a server fault");
        assertEquals("error.gitSource.refNotFound", error.messageCode(),
                "no branch is close to \"master\", so nothing is suggested");
    }

    @Test
    void suggestsTheClosestBranchWhenTheConfiguredOneLooksLikeATypo() throws IOException {
        GitSyncProperties properties = properties();
        GitCommandRunner git = runner(properties);
        GitMirrorStore mirrors = new GitMirrorStore(git, properties);
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(mirrors);
        Path repo = createRepository("main");
        git.run(repo, List.of("branch", "develop"), Map.of());
        GitRepositoryRef reference = reference(repo, 5L);

        GitRefNotFoundException error = assertThrows(GitRefNotFoundException.class,
                () -> fetcher.resolveRef(reference, "mian"));

        assertEquals("main", error.suggestion());
        assertEquals("error.gitSource.refNotFoundWithSuggestion", error.messageCode());
    }

    @Test
    void followsTheRepositoryDefaultBranchWhenTheRefIsEmpty() throws IOException {
        GitSyncProperties properties = properties();
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(
                new GitMirrorStore(runner(properties), properties));
        // A default branch that is not called "main" is exactly the case an empty
        // ref has to cope with.
        Path repo = createRepository("develop");
        GitRepositoryRef reference = reference(repo, 8L);

        GitCommitRef byName = fetcher.resolveRef(reference, "develop");
        GitCommitRef byNull = fetcher.resolveRef(reference, null);
        GitCommitRef byBlank = fetcher.resolveRef(reference, "  ");

        assertEquals(byName.commitSha(), byNull.commitSha());
        assertEquals(byName.commitSha(), byBlank.commitSha());
        assertEquals("develop", byNull.ref(), "the default branch is reported by its real name");
        assertEquals("develop", byBlank.ref());
    }

    @Test
    void reportsAnEmptyRepositoryInsteadOfFailingInsideGit() throws IOException {
        GitSyncProperties properties = properties();
        GitCommandRunner git = runner(properties);
        Path empty = tempDir.resolve("empty.git");
        Files.createDirectories(empty);
        git.run(empty, List.of("init", "--bare", "--quiet", "."), Map.of());
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(new GitMirrorStore(git, properties));
        GitRepositoryRef reference = reference(empty, 9L);

        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> fetcher.resolveRef(reference, ""));
        assertEquals("error.gitSource.emptyRepository", error.messageCode());
    }

    @Test
    void deletingASourceRemovesItsMirror() throws IOException {
        GitSyncProperties properties = properties();
        GitMirrorStore mirrors = new GitMirrorStore(runner(properties), properties);
        GitCliRepositoryFetcher fetcher = new GitCliRepositoryFetcher(mirrors);
        Path repo = createRepository();

        fetcher.resolveRef(reference(repo, 5L), "main");
        assertTrue(Files.isDirectory(mirrors.mirrorPath(5L)));

        mirrors.delete(5L);
        assertFalse(Files.exists(mirrors.mirrorPath(5L)));
    }

    /** Records which git subcommands ran, so tests can assert what was avoided. */
    private static final class RecordingGitCommandRunner extends GitCommandRunner {

        private final List<String> subcommands = new java.util.ArrayList<>();

        RecordingGitCommandRunner(GitSyncProperties properties) {
            super(properties);
        }

        @Override
        public byte[] run(Path workingDirectory, List<String> arguments, Map<String, String> environment) {
            if (!arguments.isEmpty()) {
                subcommands.add(arguments.getFirst());
            }
            return super.run(workingDirectory, arguments, environment);
        }

        List<String> subcommands() {
            return List.copyOf(subcommands);
        }

        void clear() {
            subcommands.clear();
        }
    }

    private static String fingerprint(List<com.iflytek.skillhub.gitsync.scan.DiscoveredSkill> skills, String itemPath) {
        return skills.stream()
                .filter(skill -> skill.itemPath().equals(itemPath))
                .map(com.iflytek.skillhub.gitsync.scan.DiscoveredSkill::treeSha)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no skill discovered at " + itemPath));
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
    }
}
