package com.iflytek.skillhub.gitsync.git;

/**
 * Everything needed to read one repository.
 *
 * @param sourceId   id of the configured git source; also the id of its local mirror
 * @param repoUrl    repository URL exactly as configured (HTTPS, SSH or scp-like)
 * @param repoPath   {@code owner/repo} or {@code group/subgroup/repo}, derived from the URL
 * @param credential credentials for the fetch, {@link GitCredential#anonymous()} for public repositories
 */
public record GitRepositoryRef(Long sourceId, String repoUrl, String repoPath, GitCredential credential) {

    public GitRepositoryRef {
        if (sourceId == null) {
            throw new IllegalArgumentException("Git source id is required");
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required");
        }
        repoUrl = repoUrl.trim();
        repoPath = repoPath == null || repoPath.isBlank() ? GitRepoUrls.repoPath(repoUrl) : repoPath.trim();
        credential = credential == null ? GitCredential.anonymous() : credential;
        requireMatchingCredential(repoUrl, credential);
    }

    /**
     * The repository URL decides how to authenticate, so a token on an SSH remote
     * (or a key on an HTTPS remote) is a configuration error worth reporting
     * before git produces an unreadable authentication failure.
     */
    private static void requireMatchingCredential(String repoUrl, GitCredential credential) {
        if (credential.isAnonymous()) {
            return;
        }
        boolean sshRemote = "ssh".equals(GitRepoUrls.parse(repoUrl).scheme());
        if (sshRemote && credential.kind() != GitCredential.Kind.SSH) {
            throw new GitProviderException("SSH repository " + repoUrl
                    + " needs an SSH key credential; configure the credential reference with ssh:<private-key-path>");
        }
        if (!sshRemote && credential.kind() == GitCredential.Kind.SSH) {
            throw new GitProviderException("HTTPS repository " + repoUrl
                    + " needs a token credential, but the credential reference is an SSH key;"
                    + " remove the ssh: prefix or use an ssh:// repository URL");
        }
    }

    /** URL git clones or fetches from. */
    public String cloneUrl() {
        return GitRepoUrls.cloneUrl(repoUrl);
    }

    /** Browsable repository URL for links in the UI. */
    public String webUrl() {
        return GitRepoUrls.webUrl(repoUrl);
    }
}
