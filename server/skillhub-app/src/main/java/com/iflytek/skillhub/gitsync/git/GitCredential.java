package com.iflytek.skillhub.gitsync.git;

/**
 * How the sync authenticates against a repository.
 *
 * <p>Which kind applies is decided by the repository URL: HTTPS remotes use a
 * token, SSH remotes use a private key. The credential is the value typed on the
 * Git source page, so SSH keys are held as text and only written to a private
 * temporary file while a git command runs.
 *
 * @param kind       authentication mechanism
 * @param username   user name for HTTPS basic auth; never used for SSH
 * @param secret     token or password; never logged
 * @param privateKey SSH private key in OpenSSH or PEM form, when {@code kind} is {@link Kind#SSH}
 */
public record GitCredential(Kind kind, String username, String secret, String privateKey) {

    public enum Kind {
        /** Public repository, no credentials. */
        ANONYMOUS,
        /** HTTPS with a user name and token (or password). */
        TOKEN,
        /** SSH with a private key. */
        SSH
    }

    private static final GitCredential ANONYMOUS = new GitCredential(Kind.ANONYMOUS, null, null, null);

    public static GitCredential anonymous() {
        return ANONYMOUS;
    }

    public static GitCredential token(String username, String secret) {
        return new GitCredential(Kind.TOKEN, username, secret, null);
    }

    /** @param privateKey the key itself, as pasted into the form */
    public static GitCredential sshKey(String privateKey) {
        return new GitCredential(Kind.SSH, null, null, privateKey);
    }

    public boolean isAnonymous() {
        return kind == Kind.ANONYMOUS;
    }
}
