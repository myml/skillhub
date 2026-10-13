package com.iflytek.skillhub.gitsync.git;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The repository URL decides how to authenticate, so a credential whose kind does
 * not match the remote must be reported before git fails with an unreadable error.
 */
class GitRepositoryRefTest {

    private static final String SSH_URL = "git@gitlab.internal:group/repo.git";
    private static final String HTTPS_URL = "https://gitlab.internal/group/repo.git";

    @Test
    void acceptsAnAnonymousRepositoryRef() {
        GitRepositoryRef ref = new GitRepositoryRef(1L, HTTPS_URL, null, GitCredential.anonymous());

        assertEquals("group/repo", ref.repoPath());
        assertTrue(ref.credential().isAnonymous());
    }

    @Test
    void acceptsMatchingCredentialKinds() {
        assertDoesNotThrow(() -> new GitRepositoryRef(1L, HTTPS_URL, null, GitCredential.token(null, "secret")));
        assertDoesNotThrow(() -> new GitRepositoryRef(1L, SSH_URL, null, GitCredential.sshKey("-----BEGIN OPENSSH PRIVATE KEY-----abc")));
        // A public SSH repository needs no credential at all.
        assertDoesNotThrow(() -> new GitRepositoryRef(1L, SSH_URL, null, null));
    }

    @Test
    void rejectsATokenForAnSshRemote() {
        GitProviderException error = assertThrows(GitProviderException.class,
                () -> new GitRepositoryRef(1L, SSH_URL, null, GitCredential.token(null, "secret")));

        assertTrue(error.getMessage().contains("needs an SSH key credential"), error.getMessage());
    }

    @Test
    void rejectsAnSshKeyForAnHttpsRemote() {
        GitProviderException error = assertThrows(GitProviderException.class,
                () -> new GitRepositoryRef(1L, HTTPS_URL, null, GitCredential.sshKey("-----BEGIN OPENSSH PRIVATE KEY-----abc")));

        assertTrue(error.getMessage().contains("needs a token credential"), error.getMessage());
    }

    @Test
    void rejectsAMissingRepositoryUrl() {
        assertThrows(IllegalArgumentException.class, () -> new GitRepositoryRef(1L, "  ", null, null));
    }
}
