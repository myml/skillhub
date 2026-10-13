package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.gitsync.git.GitProviderException;
import com.iflytek.skillhub.gitsync.git.GitRepoUrls;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * Decides whether a repository may be fetched, before anything touches the
 * network.
 *
 * <p>Only {@code https} and {@code ssh} remotes are accepted, and the host must
 * be listed by an operator in {@code skillhub.gitsync.url.allowed-hosts}. An
 * empty list denies everything: a source registration must never let a caller
 * point the server at an arbitrary address (an internal service, a metadata
 * endpoint, or - since bare mirrors live on this host - another source's
 * mirror).
 */
@Component
public class GitSyncUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(GitSyncUrlPolicy.class);

    private final Set<String> allowedHosts;

    public GitSyncUrlPolicy(GitSyncProperties properties) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String host : properties.getUrl().getAllowedHosts()) {
            if (host != null && !host.isBlank()) {
                hosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowedHosts = Set.copyOf(hosts);
    }

    /**
     * An empty list is a valid configuration - it denies everything - but it is
     * almost always a missing environment variable, so say so once at startup
     * instead of letting the first sync fail unnoticed.
     */
    @PostConstruct
    void reportConfiguration() {
        if (allowedHosts.isEmpty()) {
            log.warn("No git source host is allowed: every source will be rejected. "
                    + "Configure skillhub.gitsync.url.allowed-hosts "
                    + "(SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS) to enable syncing.");
        } else {
            log.info("Git sources may be fetched from: {}", String.join(", ", allowedHosts));
        }
    }

    /**
     * @throws DomainBadRequestException when the address may not be fetched
     */
    public void requireAllowed(String repoUrl) {
        GitRepoUrls.RepoLocation location;
        try {
            location = GitRepoUrls.parse(repoUrl);
        } catch (GitProviderException ex) {
            // A malformed address is reported by the caller's own validation.
            return;
        }
        String scheme = location.scheme();
        if (!"https".equals(scheme) && !"ssh".equals(scheme)) {
            throw new DomainBadRequestException("error.gitSource.url.scheme");
        }
        String host = hostOnly(location.host());
        if (!allowedHosts.contains(host)) {
            if (allowedHosts.isEmpty()) {
                throw new DomainBadRequestException("error.gitSource.url.notAllowedEmpty", host);
            }
            throw new DomainBadRequestException("error.gitSource.url.notAllowed", host,
                    String.join(", ", allowedHosts));
        }
    }

    /** Configured hosts, for messages and diagnostics. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * {@code GitRepoUrls} keeps the authority as written, so a remote on a
     * non-standard port arrives here as {@code host:2222}.
     */
    private static String hostOnly(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            return value.substring(0, colon);
        }
        return value;
    }
}
