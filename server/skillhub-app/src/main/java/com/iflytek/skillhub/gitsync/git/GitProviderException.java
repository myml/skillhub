package com.iflytek.skillhub.gitsync.git;

import java.util.regex.Pattern;

/**
 * Raised when a git hosting provider cannot satisfy a request.
 *
 * <p>The message is what gets logged and what lands in a run's history, so any
 * credential embedded in a remote URL is masked before the text is stored: git
 * echoes the URL it dialled in its own error output, and administrators do paste
 * {@code https://user:token@host/repo} into the address field.
 */
public class GitProviderException extends RuntimeException {

    /**
     * {@code scheme://user:secret@host} keeps the scheme and host and drops the
     * credential. A user name without a password ({@code ssh://git@host}) is left
     * alone: it is not a secret and the error reads better with it.
     */
    private static final Pattern URL_CREDENTIAL =
            Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)([^/@\\s:]+:[^/@\\s]+)@");

    public GitProviderException(String message) {
        super(redact(message));
    }

    public GitProviderException(String message, Throwable cause) {
        super(redact(message), cause);
    }

    /** Masks the password part of any URL in the text. */
    public static String redact(String message) {
        if (message == null) {
            return null;
        }
        return URL_CREDENTIAL.matcher(message).replaceAll("$1***@");
    }
}
