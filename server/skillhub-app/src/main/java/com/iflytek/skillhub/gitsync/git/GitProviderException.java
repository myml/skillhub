package com.iflytek.skillhub.gitsync.git;

/**
 * Raised when a git hosting provider cannot satisfy a request.
 */
public class GitProviderException extends RuntimeException {

    public GitProviderException(String message) {
        super(message);
    }

    public GitProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
