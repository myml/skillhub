package com.iflytek.skillhub.gitsync.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GitRepoUrlsTest {

    @Test
    void derivesRepoPathFromHttpsUrl() {
        assertEquals("owner/repo", GitRepoUrls.repoPath("https://github.com/owner/repo"));
        assertEquals("owner/repo", GitRepoUrls.repoPath("https://github.com/owner/repo.git"));
    }

    @Test
    void keepsNestedGitLabGroups() {
        assertEquals("group/sub/repo", GitRepoUrls.repoPath("https://gitlab.internal/group/sub/repo.git"));
    }

    @Test
    void acceptsScpLikeSshRemotes() {
        assertEquals("group/repo", GitRepoUrls.repoPath("git@gitlab.internal:group/repo.git"));
        assertEquals("gitlab.internal", GitRepoUrls.host("git@gitlab.internal:group/repo.git"));
        assertEquals("git@gitlab.internal:group/repo.git", GitRepoUrls.cloneUrl("git@gitlab.internal:group/repo.git"));
    }

    @Test
    void acceptsSshUrlsWithUserAndPort() {
        assertEquals("group/repo", GitRepoUrls.repoPath("ssh://git@gitlab.internal:2222/group/repo.git"));
        // The port is not part of the host: it is the SSH endpoint, not the web one.
        assertEquals("gitlab.internal", GitRepoUrls.host("ssh://git@gitlab.internal:2222/group/repo.git"));
    }

    @Test
    void readsScpLikePathsLiterallyIncludingPortLikeSegments() {
        // "git@host:2222/x" is the path "2222/x" in scp syntax, not port 2222.
        assertEquals("2222/x", GitRepoUrls.repoPath("git@gitlab.internal:2222/x"));
        assertEquals("gitlab.internal", GitRepoUrls.host("git@gitlab.internal:2222/x"));
    }

    @Test
    void keepsLiteralCharactersInScpLikePaths() {
        assertEquals("a#b", GitRepoUrls.repoPath("git@gitlab.internal:a#b"));
        assertEquals("a?b", GitRepoUrls.repoPath("git@gitlab.internal:a?b"));
        assertEquals("100%25", GitRepoUrls.repoPath("git@gitlab.internal:100%25"));
    }

    @Test
    void takesTheHostFromTheAuthorityRatherThanAfterAnAtSign() {
        // A fragment or query never reaches the server, so the host has to come
        // from the authority. Reading it by hand here would name other.example.com
        // and let an allow-list check approve a fetch to gitlab.corp.example.com.
        assertEquals("gitlab.corp.example.com",
                GitRepoUrls.host("https://gitlab.corp.example.com/x#@other.example.com/repo"));
        assertEquals("gitlab.corp.example.com",
                GitRepoUrls.host("https://gitlab.corp.example.com/x?ref=@other.example.com"));
        // The part after the delimiter is not the path either.
        assertEquals("x", GitRepoUrls.repoPath("https://gitlab.corp.example.com/x#@other.example.com/repo"));
        // Without a path before the delimiter the URL is rejected outright.
        assertThrows(GitProviderException.class,
                () -> GitRepoUrls.parse("https://gitlab.corp.example.com#@other.example.com/group/repo"));
        assertThrows(GitProviderException.class,
                () -> GitRepoUrls.parse("https://gitlab.corp.example.com?x=@other.example.com/group/repo"));
    }

    @Test
    void acceptsUserInformationAndTrailingDotHosts() {
        assertEquals("gitlab.internal", GitRepoUrls.host("https://someone@gitlab.internal/group/repo"));
        assertEquals("gitlab.internal.", GitRepoUrls.host("https://gitlab.internal./group/repo"));
    }

    @Test
    void rejectsHostsUriWillNotAccept() {
        // Deliberately unsupported rather than guessed at: underscores are not a
        // legal host name and reading one back by hand is how a host check starts
        // disagreeing with git.
        assertThrows(GitProviderException.class, () -> GitRepoUrls.parse("https://my_host.corp/group/repo"));
        assertThrows(GitProviderException.class, () -> GitRepoUrls.parse("ssh://git@my_host.corp/group/repo"));
    }

    @Test
    void rejectsUpperCasedSchemesLikeGitDoes() {
        // git looks for a remote helper called HTTPS, so accepting this would
        // register a source that can never be fetched.
        assertThrows(GitProviderException.class, () -> GitRepoUrls.parse("HTTPS://github.com/owner/repo"));
    }

    @Test
    void acceptsLocalFileUrlsForTestingAndMirrors() {
        assertEquals("tmp/repo", GitRepoUrls.repoPath("file:///tmp/repo"));
        assertEquals("file:///tmp/repo", GitRepoUrls.cloneUrl("file:///tmp/repo"));
    }

    @Test
    void rejectsSomethingThatIsNotARepositoryUrl() {
        assertThrows(GitProviderException.class, () -> GitRepoUrls.parse("github.com/owner/repo"));
        assertThrows(GitProviderException.class, () -> GitRepoUrls.parse(""));
        assertThrows(GitProviderException.class, () -> GitRepoUrls.parse("https://github.com"));
    }

    @Test
    void stripsGitSuffixForWebUrl() {
        assertEquals("https://github.com/owner/repo", GitRepoUrls.webUrl("https://github.com/owner/repo.git"));
    }

    @Test
    void showsSshRemotesOverHttps() {
        assertEquals("https://gitlab.internal/group/repo", GitRepoUrls.webUrl("git@gitlab.internal:group/repo.git"));
        // The SSH port says nothing about the web endpoint, so the icon/link does
        // not inherit a port that would not answer over HTTPS.
        assertEquals("https://gitlab.internal/group/repo",
                GitRepoUrls.webUrl("ssh://git@gitlab.internal:2222/group/repo.git"));
        assertEquals("http://gitlab.internal/group/repo",
                GitRepoUrls.webUrl("http://gitlab.internal/group/repo.git"));
    }



}
