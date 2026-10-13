package com.iflytek.skillhub.gitsync;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for git-repository syncing.
 */
@ConfigurationProperties(prefix = "skillhub.gitsync")
public class GitSyncProperties {

    /**
     * Directory holding one bare mirror per source. Each mirror is a full
     * {@code git} repository, so pick a location with room for the repositories
     * being synced.
     */
    private Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "skillhub-git-sources");

    /** git executable; override when git is not on the PATH of the runtime user. */
    private String gitBinary = "git";

    /** Upper bound for a single git command, e.g. a clone or fetch. */
    private Duration commandTimeout = Duration.ofMinutes(10);

    /** Fetch depth; 1 keeps only the synced commit, 0 fetches full history. */
    private int fetchDepth = 1;

    /** Where a source may be fetched from. */
    private final Url url = new Url();

    /** How much syncing is allowed to cost. */
    private final Limits limits = new Limits();

    /**
     * Quotas. Both default to {@code 0}, which means "no limit": existing
     * installations keep working, and an operator opts in explicitly.
     */
    public static class Limits {

        /** Sources one user may register; 0 disables the check. */
        private int maxSourcesPerUser;

        /** Bytes one source's mirror may occupy; 0 disables the check. */
        private long maxMirrorBytes;

        public int getMaxSourcesPerUser() {
            return maxSourcesPerUser;
        }

        public void setMaxSourcesPerUser(int maxSourcesPerUser) {
            this.maxSourcesPerUser = maxSourcesPerUser;
        }

        public long getMaxMirrorBytes() {
            return maxMirrorBytes;
        }

        public void setMaxMirrorBytes(long maxMirrorBytes) {
            this.maxMirrorBytes = maxMirrorBytes;
        }
    }

    public Limits getLimits() {
        return limits;
    }

    /**
     * Remote address policy.
     *
     * <p>Only {@code https} and {@code ssh} remotes are accepted, and the host
     * must be listed here by its exact name (case-insensitive). An empty list
     * denies every source: the registry never fetches from a host an operator
     * did not name, which is what keeps a source registration from turning the
     * server into a reader of arbitrary internal services.
     */
    public static class Url {

        /** Allowed remote hosts, for example {@code github.com,gitlab.corp.example.com}. */
        private List<String> allowedHosts = new ArrayList<>();

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
        }
    }

    public Url getUrl() {
        return url;
    }


    public Path getWorkDir() {
        return workDir;
    }

    public void setWorkDir(Path workDir) {
        this.workDir = workDir;
    }

    public String getGitBinary() {
        return gitBinary;
    }

    public void setGitBinary(String gitBinary) {
        this.gitBinary = gitBinary;
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    public int getFetchDepth() {
        return fetchDepth;
    }

    public void setFetchDepth(int fetchDepth) {
        this.fetchDepth = fetchDepth;
    }
}
