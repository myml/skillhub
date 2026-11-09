package com.iflytek.skillhub.gitsync;

import com.iflytek.skillhub.gitsync.git.GitCredential;
import com.iflytek.skillhub.gitsync.git.GitProviderException;
import com.iflytek.skillhub.gitsync.git.GitRepoUrls;
import com.iflytek.skillhub.gitsync.store.GitSourceRecord;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the credential for a source from the value typed on the Git source page.
 *
 * <p>There are exactly two shapes, chosen by the repository URL:
 * <ul>
 *   <li>HTTP(S) remote: {@code <user>:<deploy credential>} (a bare value is also
 *       accepted and sent as the password of an empty user name).</li>
 *   <li>SSH remote: the private key itself, pasted as text.</li>
 * </ul>
 * An empty value means a public repository, which is fetched anonymously.
 */
@Component
public class GitCredentialResolver {

    public GitCredential resolve(GitSourceRecord source) {
        return resolve(source.repoUrl(), source.credential());
    }

    /**
     * Credential for a repository that has not been saved yet, so the form can be
     * validated against the real server before the source exists.
     */
    public GitCredential resolve(String repoUrl, String credentialValue) {
        if (!StringUtils.hasText(credentialValue)) {
            return GitCredential.anonymous();
        }
        if (isSshRemote(repoUrl)) {
            return GitCredential.sshKey(credentialValue);
        }
        return parseToken(credentialValue.trim());
    }

    /** Package private for tests. */
    static GitCredential parseToken(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            // "token" alone: GitHub and GitLab accept any user name with a token.
            return GitCredential.token(null, value);
        }
        return GitCredential.token(value.substring(0, colon), value.substring(colon + 1));
    }

    static boolean isSshRemote(String repoUrl) {
        try {
            return "ssh".equals(GitRepoUrls.parse(repoUrl).scheme());
        } catch (GitProviderException ex) {
            // Let the URL validation report the malformed URL.
            return false;
        }
    }
}
