package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitSyncUrlPolicyTest {

    private static GitSyncUrlPolicy policy(String... hosts) {
        GitSyncProperties properties = new GitSyncProperties();
        properties.getUrl().setAllowedHosts(List.of(hosts));
        return new GitSyncUrlPolicy(properties);
    }

    @Test
    void allowsAnAllowListedHttpsHost() {
        assertDoesNotThrow(() -> policy("github.com").requireAllowed("https://github.com/owner/repo"));
    }

    @Test
    void allowsAnAllowListedSshHostInBothSyntaxes() {
        GitSyncUrlPolicy policy = policy("gitlab.corp.example.com");

        assertDoesNotThrow(() -> policy.requireAllowed("git@gitlab.corp.example.com:group/repo.git"));
        assertDoesNotThrow(() -> policy.requireAllowed("ssh://git@gitlab.corp.example.com/group/repo.git"));
    }

    @Test
    void matchesTheHostCaseInsensitivelyAndIgnoresThePort() {
        GitSyncUrlPolicy policy = policy("gitlab.corp.example.com");

        assertDoesNotThrow(() -> policy.requireAllowed("https://GitLab.Corp.Example.com:8443/group/repo"));
        assertDoesNotThrow(() -> policy.requireAllowed("ssh://git@gitlab.corp.example.com:2222/group/repo.git"));
    }

    @Test
    void rejectsAHostThatIsNotListedAndNamesTheAllowedOnes() {
        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> policy("github.com").requireAllowed("https://gitlab.corp.example.com/group/repo"));

        assertEquals("error.gitSource.url.notAllowed", error.messageCode());
        assertEquals("gitlab.corp.example.com", error.messageArgs()[0]);
        assertEquals("github.com", error.messageArgs()[1]);
    }

    @Test
    void rejectsEverythingWhenNoHostIsConfigured() {
        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> policy().requireAllowed("https://github.com/owner/repo"));

        assertEquals("error.gitSource.url.notAllowedEmpty", error.messageCode());
        assertEquals("github.com", error.messageArgs()[0]);
    }

    @Test
    void rejectsPlainHttpEvenForAnAllowedHost() {
        DomainBadRequestException error = assertThrows(DomainBadRequestException.class,
                () -> policy("gitlab.corp.example.com").requireAllowed("http://gitlab.corp.example.com/group/repo"));

        assertEquals("error.gitSource.url.scheme", error.messageCode());
    }

    @Test
    void rejectsLocalRepositoriesEvenWhenTheHostWouldMatch() {
        // file:// has no host, so it can never be allow-listed.
        assertThrows(DomainBadRequestException.class,
                () -> policy("", "localhost", "file").requireAllowed("file:///srv/git/repo.git"));
    }

    @Test
    void ignoresBlankEntriesAndReportsTheConfiguredHosts() {
        assertEquals(List.of("github.com"), List.copyOf(policy("  ", "GitHub.com").allowedHosts()));
    }

    @Test
    void leavesMalformedAddressesToTheCallersOwnValidation() {
        assertDoesNotThrow(() -> policy("github.com").requireAllowed("not a url"));
        assertTrue(policy("github.com").allowedHosts().contains("github.com"));
    }
}
