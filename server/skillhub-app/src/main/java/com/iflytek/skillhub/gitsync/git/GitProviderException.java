package com.iflytek.skillhub.gitsync.git;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Raised when a git hosting provider cannot satisfy a request.
 *
 * <p>The message is what gets logged and what lands in a run's history, so any
 * credential embedded in a remote URL is masked before the text is stored: git
 * echoes the URL it dialled in its own error output, and administrators do paste
 * {@code https://user:token@host/repo} into the address field.
 *
 * <p>Git's own stderr is the only place that says <em>why</em> it failed, and it
 * is not ambiguous to a human: "Authentication failed" is a credential problem,
 * "Repository not found" is an address or permission problem, and a connect
 * timeout is neither. {@link #kind()} turns that text into a category the API can
 * report separately, so a wrong deploy token no longer reads as "network error,
 * please try again later". The phrases are English because
 * {@link GitCommandRunner} pins {@code LC_ALL=C} for every git invocation.
 */
public class GitProviderException extends RuntimeException {

    /** What the failure means for the administrator, not for the transport. */
    public enum Kind {
        /** The remote refused the credential: wrong token, expired, or no access. */
        AUTHENTICATION,
        /** The repository is not there, or the credential may not see it. */
        NOT_FOUND,
        /** Anything else: unreachable host, timeout, missing git, unknown failure. */
        UPSTREAM
    }

    /**
     * {@code scheme://user:secret@host} keeps the scheme and host and drops the
     * credential. A user name without a password ({@code ssh://git@host}) is left
     * alone: it is not a secret and the error reads better with it.
     */
    private static final Pattern URL_CREDENTIAL =
            Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)([^/@\\s:]+:[^/@\\s]+)@");

    /** Phrases git, GitHub, GitLab and Gitea use when a credential is refused. */
    private static final List<String> AUTHENTICATION_SIGNS = List.of(
            "authentication failed",
            "invalid username or token",
            "invalid username or password",
            "invalid credentials",
            "could not read username",
            "could not read password",
            "terminal prompts disabled",
            "permission denied (publickey",
            "permission denied, please try again",
            "http basic: access denied",
            "access denied",
            "not allowed to download code",
            "returned error: 401",
            "returned error: 403");

    /** Phrases that mean the path does not resolve to a repository the caller can see. */
    private static final List<String> NOT_FOUND_SIGNS = List.of(
            "repository not found",
            "project not found",
            "does not appear to be a git repository",
            "no such repository",
            "returned error: 404");

    private final Kind kind;

    public GitProviderException(String message) {
        this(message, null);
    }

    public GitProviderException(String message, Throwable cause) {
        super(redact(message), cause);
        this.kind = classify(getMessage());
    }

    /** Category of the failure, derived from git's own output. */
    public Kind kind() {
        return kind;
    }

    /** Masks the password part of any URL in the text. */
    public static String redact(String message) {
        if (message == null) {
            return null;
        }
        return URL_CREDENTIAL.matcher(message).replaceAll("$1***@");
    }

    /**
     * Classifies a git failure by the text git wrote. Authentication is checked
     * first: a private repository the caller cannot see also reports "not found",
     * and pointing at the credential is the more useful of the two hints.
     */
    static Kind classify(String message) {
        if (message == null || message.isBlank()) {
            return Kind.UPSTREAM;
        }
        String text = message.toLowerCase(Locale.ROOT);
        for (String sign : AUTHENTICATION_SIGNS) {
            if (text.contains(sign)) {
                return Kind.AUTHENTICATION;
            }
        }
        for (String sign : NOT_FOUND_SIGNS) {
            if (text.contains(sign)) {
                return Kind.NOT_FOUND;
            }
        }
        return Kind.UPSTREAM;
    }
}
