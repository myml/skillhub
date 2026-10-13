package com.iflytek.skillhub.gitsync.git;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Parses repository locations and derives the URLs shown to users.
 *
 * <p>Because syncing goes through git itself, the repository URL is the only
 * location information a source needs: HTTPS, {@code ssh://} and scp-like
 * {@code git@host:path} remotes are all accepted, and no provider API endpoint
 * has to be configured.
 *
 * <p>git re-parses the value it is handed, so the host returned here is only
 * trustworthy if it matches what git will dial. {@link URI} is therefore the
 * parser of record for every URL form and scp-like remotes are rewritten into
 * their {@code ssh://} equivalent first. A host name {@code URI} will not
 * accept (underscores, non-ASCII, stray delimiters) is rejected rather than
 * guessed at.
 */
public final class GitRepoUrls {

    private GitRepoUrls() {
    }

    /**
     * @param scheme   {@code http}, {@code https}, {@code ssh} or {@code file}
     * @param sshUser  user name of an scp-like remote, e.g. {@code git} in {@code git@host:path}
     * @param host     host name without a port; empty for local paths
     * @param path     repository path without leading or trailing slashes and without {@code .git}
     * @param cloneUrl the URL git should use, exactly as configured
     */
    public record RepoLocation(String scheme, String sshUser, String host, String path, String cloneUrl) {
    }

    public static RepoLocation parse(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new GitProviderException("Repository URL is required");
        }
        String value = repoUrl.trim();

        // Only lower-case schemes are recognised, which is what git itself does:
        // "HTTPS://host/repo" makes git look for a remote helper called HTTPS.
        for (String scheme : new String[] {"http", "https", "ssh", "file"}) {
            if (value.startsWith(scheme + "://")) {
                return parseUri(value, scheme, value);
            }
        }

        // scp-like syntax: git@host:path is git's shorthand for ssh://git@host/path.
        // Everything after the first colon is the path, so "git@host:2222/x" means
        // the path "2222/x" rather than port 2222 - converting the colon into a
        // slash keeps that reading, and URI then has to be told the path is not
        // percent-encoded.
        int at = value.indexOf('@');
        int colon = value.indexOf(':', at + 1);
        if (at > 0 && colon > at + 1) {
            String scpPath = escapeForUri(value.substring(colon + 1));
            return parseUri("ssh://" + value.substring(0, colon) + "/" + scpPath, "ssh", value);
        }

        throw new GitProviderException("Unsupported repository URL; use an HTTP(S), ssh://, "
                + "git@host:path or file:// URL: " + repoUrl);
    }

    /** {@code owner/repo} or {@code group/subgroup/repo}. */
    public static String repoPath(String repoUrl) {
        return parse(repoUrl).path();
    }

    public static String host(String repoUrl) {
        return parse(repoUrl).host();
    }

    /** URL git should clone or fetch from. */
    public static String cloneUrl(String repoUrl) {
        return parse(repoUrl).cloneUrl();
    }

    /**
     * Browsable repository URL: SSH remotes are shown over HTTPS because that is
     * where a human can open the link. The SSH port is dropped with it, since it
     * says nothing about the web endpoint.
     */
    public static String webUrl(String repoUrl) {
        RepoLocation location = parse(repoUrl);
        if ("file".equals(location.scheme())) {
            return location.cloneUrl();
        }
        String scheme = "http".equals(location.scheme()) ? "http" : "https";
        return scheme + "://" + location.host() + "/" + location.path();
    }

    private static RepoLocation parseUri(String value, String scheme, String original) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new GitProviderException("Invalid repository URL: " + original);
        }

        String path = normalizePath(uri.getPath());
        if ("file".equals(scheme)) {
            if (path.isBlank()) {
                throw new GitProviderException("Repository URL must include the repository path: " + original);
            }
            return new RepoLocation(scheme, null, "", path, original);
        }

        if (path.isBlank()) {
            throw new GitProviderException("Repository URL must include the repository path: " + original);
        }
        return new RepoLocation(scheme, uri.getUserInfo(), hostOf(uri, original), path, original);
    }

    /**
     * The host git will dial, without user information and without a port.
     *
     * <p>A fragment or query never reaches the server, so a URL such as
     * {@code https://gitlab.corp.example.com#@other.example.com/repo} must be
     * reported as {@code gitlab.corp.example.com}: reading the authority by hand
     * would name {@code other.example.com} instead, and an allow-list check on
     * that name would let the fetch reach a host it never approved.
     */
    private static String hostOf(URI uri, String original) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new GitProviderException("Invalid repository host in URL: " + original);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * scp-like paths are literal, so a {@code %} must not be read as the start of
     * a percent-encoded escape once the value becomes a URI.
     */
    private static String escapeForUri(String path) {
        return path.replace("%", "%25").replace("#", "%23").replace("?", "%3F");
    }

    private static String normalizePath(String path) {
        String result = path == null ? "" : path;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith(".git")) {
            result = result.substring(0, result.length() - 4);
        }
        return result;
    }
}
