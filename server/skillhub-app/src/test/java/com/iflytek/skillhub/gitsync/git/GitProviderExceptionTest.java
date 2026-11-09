package com.iflytek.skillhub.gitsync.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The git error text is logged and stored in run history, so a credential pasted
 * into a repository URL must not survive into it.
 */
class GitProviderExceptionTest {

    @Test
    void masksThePasswordOfAUrlInTheGitOutput() {
        GitProviderException error = new GitProviderException(
                "git ls-remote failed: fatal: unable to access "
                        + "'https://gitlab+deploy-token-220:glpat-secret@gitlab.internal/wurongjie/repo/': 401");

        assertEquals("git ls-remote failed: fatal: unable to access "
                        + "'https://***@gitlab.internal/wurongjie/repo/': 401",
                error.getMessage());
    }

    @Test
    void masksASecretOnAnySchemeAndUrlInTheText() {
        assertEquals("ssh://***@host/repo and git@host:repo",
                GitProviderException.redact("ssh://user:key@host/repo and git@host:repo"));
    }

    @Test
    void keepsAUrlWithoutAPasswordReadable() {
        assertEquals("fatal: repository 'ssh://git@github.com/owner/repo' not found",
                GitProviderException.redact("fatal: repository 'ssh://git@github.com/owner/repo' not found"));
        assertEquals("fatal: could not read from https://github.com/owner/repo",
                GitProviderException.redact("fatal: could not read from https://github.com/owner/repo"));
    }

    @Test
    void tellsACredentialRejectionApartFromANetworkFailure() {
        // GitHub's wording, and the generic one git prints for https remotes.
        assertEquals(GitProviderException.Kind.AUTHENTICATION, classify(
                "git ls-remote --symref failed: remote: Invalid username or token. "
                        + "Password authentication is not supported for Git operations. "
                        + "fatal: Authentication failed for 'https://github.com/owner/repo/'"));
        assertEquals(GitProviderException.Kind.AUTHENTICATION, classify(
                "git fetch failed: fatal: could not read Username for 'https://git.example.com': "
                        + "terminal prompts disabled"));
        // GitLab answers a bad deploy token with 401, a token without scope with 403.
        assertEquals(GitProviderException.Kind.AUTHENTICATION, classify(
                "git ls-remote failed: fatal: unable to access 'https://gitlab.example.com/o/r/': "
                        + "The requested URL returned error: 401"));
        assertEquals(GitProviderException.Kind.AUTHENTICATION, classify(
                "git ls-remote failed: remote: HTTP Basic: Access denied"));
        assertEquals(GitProviderException.Kind.AUTHENTICATION, classify(
                "git ls-remote failed: git@github.com: Permission denied (publickey)."));
    }

    @Test
    void tellsAMissingRepositoryApartFromANetworkFailure() {
        assertEquals(GitProviderException.Kind.NOT_FOUND, classify(
                "git ls-remote failed: remote: Repository not found. "
                        + "fatal: repository 'https://github.com/owner/nope/' not found"));
        assertEquals(GitProviderException.Kind.NOT_FOUND, classify(
                "git ls-remote failed: fatal: '/srv/git/x.git' does not appear to be a git repository"));
        assertEquals(GitProviderException.Kind.NOT_FOUND, classify(
                "git ls-remote failed: fatal: unable to access 'https://host/o/r/': "
                        + "The requested URL returned error: 404"));
    }

    @Test
    void treatsAnUnreachableHostAsAnUpstreamFailure() {
        assertEquals(GitProviderException.Kind.UPSTREAM, classify(
                "git ls-remote --symref failed: fatal: unable to access "
                        + "'https://github.com:1/o/r/': Failed to connect to github.com port 1"));
        assertEquals(GitProviderException.Kind.UPSTREAM, classify(
                "git ls-remote failed: fatal: unable to access 'https://host/o/r/': "
                        + "Could not resolve host: host"));
    }

    private static GitProviderException.Kind classify(String message) {
        return new GitProviderException(message).kind();
    }

    @Test
    void keepsPlainTextUntouched() {
        assertEquals("git fetch --prune failed: fatal: couldn't find remote ref master",
                GitProviderException.redact("git fetch --prune failed: fatal: couldn't find remote ref master"));
    }
}
