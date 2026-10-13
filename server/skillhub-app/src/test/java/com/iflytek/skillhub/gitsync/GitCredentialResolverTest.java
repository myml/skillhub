package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iflytek.skillhub.gitsync.git.GitCredential;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import org.junit.jupiter.api.Test;

/**
 * There are two credential shapes and the repository URL picks one: a deploy
 * token for HTTP(S) remotes, the private key itself for SSH remotes. An empty
 * value means a public repository.
 */
class GitCredentialResolverTest {

    private final GitCredentialResolver resolver = new GitCredentialResolver();

    @Test
    void fetchesAnonymouslyWithoutACredential() {
        assertTrue(resolver.resolve(source("https://git.internal/owner/repo", null)).isAnonymous());
        assertTrue(resolver.resolve(source("https://git.internal/owner/repo", "   ")).isAnonymous());
        assertTrue(resolver.resolve(source("git@git.internal:owner/repo.git", null)).isAnonymous());
    }

    @Test
    void readsAUserAndDeployTokenFromAnHttpsValue() {
        GitCredential credential = resolver.resolve(source("https://git.internal/owner/repo", "release-bot:glpat-secret"));

        assertEquals(GitCredential.Kind.TOKEN, credential.kind());
        assertEquals("release-bot", credential.username());
        assertEquals("glpat-secret", credential.secret());
    }

    @Test
    void acceptsABareTokenForServersThatIgnoreTheUserName() {
        GitCredential credential = resolver.resolve(source("https://github.com/owner/repo", "ghp_secret"));

        assertEquals(GitCredential.Kind.TOKEN, credential.kind());
        assertNull(credential.username());
        assertEquals("ghp_secret", credential.secret());
    }

    @Test
    void keepsColonsThatBelongToTheToken() {
        GitCredential credential = GitCredentialResolver.parseToken("user:token:with:colons");

        assertEquals("user", credential.username());
        assertEquals("token:with:colons", credential.secret());
    }

    @Test
    void usesThePastedPrivateKeyForSshRemotes() {
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----";

        GitCredential credential = resolver.resolve(source("git@git.internal:owner/repo.git", key));

        assertEquals(GitCredential.Kind.SSH, credential.kind());
        assertEquals(key, credential.privateKey());
        assertNull(credential.secret());
    }

    @Test
    void usesThePastedPrivateKeyForSshUrlsToo() {
        assertEquals(GitCredential.Kind.SSH,
                resolver.resolve(source("ssh://git@git.internal:2222/owner/repo.git", "key-material")).kind());
    }

    @Test
    void treatsTheSameValueAsATokenForAnHttpsRemote() {
        // The protocol, not the value, decides how the credential is used.
        assertEquals(GitCredential.Kind.TOKEN,
                resolver.resolve(source("https://git.internal/owner/repo", "key-material")).kind());
    }

    private static GitSourceRecord source(String repoUrl, String credential) {
        return new GitSourceRecord(1L, repoUrl, "owner/repo", "main", "", "global",
                credential, "admin-1", 60, true,
                com.iflytek.skillhub.domain.skill.SkillVisibility.PUBLIC,
                "admin-1", java.time.Instant.now(), java.time.Instant.now(), null, null, null, null);
    }
}
