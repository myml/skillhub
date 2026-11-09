package com.iflytek.skillhub.gitsync.git;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.gitsync.GitSyncProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers "does this repository exist, and does it have this ref?" without
 * downloading any content.
 *
 * <p>Used when a source is created or edited: a mistyped branch, an empty
 * repository or an unreachable host should fail on the form, not on the next
 * scheduled sync. Only {@code ls-remote} runs, so a check costs a couple of
 * round trips and no object transfer.
 */
@Component
public class GitRefInspector {

    /**
     * A form check must answer quickly: it runs inside a request, whereas a fetch
     * legitimately takes much longer.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final GitMirrorStore mirrors;
    private final GitSyncProperties properties;

    public GitRefInspector(GitMirrorStore mirrors, GitSyncProperties properties) {
        this.mirrors = mirrors;
        this.properties = properties;
    }

    /**
     * Makes sure the repository is reachable and holds the configured ref.
     *
     * @param ref branch, tag or commit to verify; empty means "the default branch",
     *            which is already proven to exist by the default-branch probe
     * @throws DomainBadRequestException when the repository has no branches
     * @throws GitRefNotFoundException  when the ref does not exist
     * @throws GitProviderException     when the server cannot be reached at all
     */
    public void requireRefExists(String repoUrl, String ref, GitCredential credential) {
        String cloneUrl = GitRepoUrls.cloneUrl(repoUrl);
        Path workDir = workDir();
        Map<String, String> environment = mirrors.credentialEnvironment(credential);

        Optional<GitMirrorStore.RemoteRef> defaultBranch =
                mirrors.lsRemoteDefaultBranch(workDir, cloneUrl, environment, PROBE_TIMEOUT);
        if (defaultBranch.isEmpty()) {
            throw new DomainBadRequestException("error.gitSource.emptyRepository");
        }

        String requested = ref == null ? "" : ref.trim();
        if (requested.isEmpty()) {
            // Following the default branch: the probe above already proved it exists.
            return;
        }
        if (mirrors.lsRemote(workDir, cloneUrl, requested, environment, PROBE_TIMEOUT).isPresent()) {
            return;
        }
        if (GitMirrorStore.isCommitId(requested)) {
            // A pinned commit is not a ref, so ls-remote cannot list it; the fetch
            // that follows reports it if the object really is missing.
            return;
        }
        List<String> branches = mirrors.availableBranches(workDir, cloneUrl, environment, PROBE_TIMEOUT);
        throw new GitRefNotFoundException(requested, branches,
                GitMirrorStore.suggestRefName(requested, branches));
    }

    /** {@code ls-remote} needs a directory to run in, not a repository. */
    private Path workDir() {
        Path workDir = properties.getWorkDir();
        try {
            Files.createDirectories(workDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not create the git work directory " + workDir, ex);
        }
        return workDir;
    }
}
