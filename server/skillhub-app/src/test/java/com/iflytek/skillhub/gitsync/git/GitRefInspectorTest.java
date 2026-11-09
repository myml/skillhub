package com.iflytek.skillhub.gitsync.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.gitsync.GitSyncProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The check that runs when a source is saved: a wrong branch, an empty repository
 * or an unreachable host has to be reported on the form, before anything is
 * stored, and the check must not download repository content.
 */
class GitRefInspectorTest {

    @TempDir
    Path tempDir;

    private GitSyncProperties properties() {
        GitSyncProperties properties = new GitSyncProperties();
        properties.setWorkDir(tempDir.resolve("work"));
        return properties;
    }

    private GitRefInspector inspector(GitSyncProperties properties) {
        GitMirrorStore mirrors = new GitMirrorStore(new GitCommandRunner(properties), properties);
        return new GitRefInspector(mirrors, properties);
    }

    /** A real repository, reached over {@code file://} like any other remote. */
    private Path repository(GitSyncProperties properties, String defaultBranch, String... extraBranches)
            throws IOException {
        Path repo = tempDir.resolve("origin");
        Files.createDirectories(repo);
        GitCommandRunner git = new GitCommandRunner(properties);
        git.run(repo, List.of("init", "--quiet", "-b", defaultBranch, "."), Map.of());
        git.run(repo, List.of("config", "user.email", "test@example.com"), Map.of());
        git.run(repo, List.of("config", "user.name", "Test"), Map.of());
        Files.writeString(repo.resolve("README.md"), "# repository\n");
        git.run(repo, List.of("add", "-A"), Map.of());
        git.run(repo, List.of("commit", "--quiet", "-m", "initial"), Map.of());
        for (String branch : extraBranches) {
            git.run(repo, List.of("branch", branch), Map.of());
        }
        return repo;
    }

    private static String url(Path repo) {
        return repo.toUri().toString();
    }

    @Test
    void acceptsTheDefaultBranchWhenTheFieldIsEmpty() throws IOException {
        GitSyncProperties properties = properties();
        Path repo = repository(properties, "main");

        inspector(properties).requireRefExists(url(repo), "", GitCredential.anonymous());
        inspector(properties).requireRefExists(url(repo), null, GitCredential.anonymous());
    }

    @Test
    void acceptsABranchThatExists() throws IOException {
        GitSyncProperties properties = properties();
        Path repo = repository(properties, "main", "develop");

        inspector(properties).requireRefExists(url(repo), "develop", GitCredential.anonymous());
    }

    @Test
    void acceptsAPinnedCommitWithoutBeingAbleToListIt() throws IOException {
        GitSyncProperties properties = properties();
        GitCommandRunner git = new GitCommandRunner(properties);
        Path repo = repository(properties, "main");
        String sha = git.runForText(repo, List.of("rev-parse", "HEAD"), Map.of()).trim();

        inspector(properties).requireRefExists(url(repo), sha, GitCredential.anonymous());
    }

    @Test
    void rejectsAMissingBranchAndNamesTheOnesThatExist() throws IOException {
        GitSyncProperties properties = properties();
        Path repo = repository(properties, "main", "develop");

        GitRefNotFoundException thrown = assertThrows(GitRefNotFoundException.class,
                () -> inspector(properties).requireRefExists(url(repo), "release/1.0", GitCredential.anonymous()));

        assertEquals("release/1.0", thrown.ref());
        assertTrue(thrown.availableBranches().containsAll(List.of("main", "develop")));
        assertEquals("error.gitSource.refNotFound", thrown.messageCode());
        assertEquals(400, thrown.statusCode());
    }

    @Test
    void suggestsTheClosestBranchForATypo() throws IOException {
        GitSyncProperties properties = properties();
        Path repo = repository(properties, "main", "develop");

        GitRefNotFoundException thrown = assertThrows(GitRefNotFoundException.class,
                () -> inspector(properties).requireRefExists(url(repo), "mian", GitCredential.anonymous()));

        assertEquals("main", thrown.suggestion());
        assertEquals("error.gitSource.refNotFoundWithSuggestion", thrown.messageCode());
        assertEquals("main", thrown.details().get("suggestion"));
    }

    @Test
    void reportsAnEmptyRepositoryInsteadOfAWithRefFailure() throws IOException {
        GitSyncProperties properties = properties();
        Path repo = tempDir.resolve("empty");
        Files.createDirectories(repo);
        GitCommandRunner git = new GitCommandRunner(properties);
        git.run(repo, List.of("init", "--quiet", "--bare", "."), Map.of());

        DomainBadRequestException thrown = assertThrows(DomainBadRequestException.class,
                () -> inspector(properties).requireRefExists(url(repo), "main", GitCredential.anonymous()));

        assertEquals("error.gitSource.emptyRepository", thrown.messageCode());
    }

    @Test
    void reportsAnUnreachableRepositoryAsAnUpstreamFailure() {
        GitSyncProperties properties = properties();
        Path missing = tempDir.resolve("does-not-exist");

        assertThrows(GitProviderException.class,
                () -> inspector(properties).requireRefExists(url(missing), "main", GitCredential.anonymous()));
    }

    @Test
    void leavesNoRepositoryBehind() throws IOException {
        GitSyncProperties properties = properties();
        Path repo = repository(properties, "main");

        inspector(properties).requireRefExists(url(repo), "main", GitCredential.anonymous());

        try (Stream<Path> entries = Files.list(properties.getWorkDir())) {
            assertEquals(List.of(), entries.toList(), "listing refs must not create or fetch a mirror");
        }
    }
}
