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
    void keepsPlainTextUntouched() {
        assertEquals("git fetch --prune failed: fatal: couldn't find remote ref master",
                GitProviderException.redact("git fetch --prune failed: fatal: couldn't find remote ref master"));
    }
}
