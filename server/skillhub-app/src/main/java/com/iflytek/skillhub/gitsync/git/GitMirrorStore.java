package com.iflytek.skillhub.gitsync.git;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.gitsync.GitSyncProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps one local bare git mirror per source.
 *
 * <p>The mirror is the only copy of the repository on the host: syncing lists and
 * reads objects from it locally, so a plan or an import touches the network once
 * per sync instead of once per file.
 *
 * <p>Mirrors are disposable caches keyed by source id. Deleting a source removes
 * its mirror; a corrupt or missing mirror is simply re-created on the next sync.
 */
@Component
public class GitMirrorStore {

    private static final Logger log = LoggerFactory.getLogger(GitMirrorStore.class);

    /** Local ref the fetched commit is parked under; objects are read by sha. */
    private static final String LOCAL_FETCH_REF = "refs/skillhub/fetched";

    /** Ref that names whatever branch the remote considers its default. */
    private static final String HEAD_REF = "HEAD";
    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String TAGS_PREFIX = "refs/tags/";
    private static final String SYMREF_PREFIX = "ref: ";

    /** How many branch names an unknown-ref error lists before truncating. */
    private static final int MAX_REPORTED_BRANCHES = 8;

    private final GitCommandRunner runner;
    private final GitSyncProperties properties;

    public GitMirrorStore(GitCommandRunner runner, GitSyncProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    public Path mirrorPath(Long sourceId) {
        return properties.getWorkDir().resolve("source-" + sourceId);
    }

    /**
     * A resolved remote reference: the object id plus the full ref name git
     * reported, which is what the fetch refspec should use.
     */
    public record RemoteRef(String sha, String name) {
    }

    /**
     * Makes sure the commit behind {@code ref} is present locally.
     *
     * <p>First a cheap {@code ls-remote} asks the server for the current commit of
     * the ref: when the mirror already holds that object nothing is downloaded at
     * all, so an unchanged repository costs one round trip. Otherwise the ref is
     * fetched at {@code fetch-depth} (1 by default) because reading a tree and its
     * blobs never needs history.
     *
     * <p>An empty {@code ref} follows the repository's own default branch: the
     * server is asked for {@code HEAD}, and its symref target names the branch, so
     * a repository whose default branch is not {@code main} needs no configuration.
     *
     * @return the immutable commit the ref points at
     */
    public GitCommitRef fetch(Long sourceId, String repoUrl, String ref, GitCredential credential) {
        Path mirror = ensureMirror(sourceId);
        String cloneUrl = GitRepoUrls.cloneUrl(repoUrl);
        Map<String, String> environment = credentialEnvironment(credential);

        boolean followDefault = ref == null || ref.isBlank();
        String requested = followDefault ? HEAD_REF : ref.trim();

        Optional<RemoteRef> remote = followDefault
                ? lsRemoteDefaultBranch(mirror, cloneUrl, environment)
                : lsRemote(mirror, cloneUrl, requested, environment);
        String resolvedRef = followDefault
                ? remote.map(RemoteRef::name).map(GitMirrorStore::shortRefName).orElse(HEAD_REF)
                : requested;

        if (remote.isPresent() && hasCommit(mirror, remote.get().sha())) {
            return new GitCommitRef(remote.get().sha(), resolvedRef);
        }
        if (remote.isEmpty()) {
            // A pinned commit is not a ref, so ls-remote cannot list it; the object
            // itself can still be fetched by id.
            requireRefExists(mirror, cloneUrl, requested, followDefault, environment);
        }

        List<String> fetch = new ArrayList<>(List.of("fetch", "--prune", "--no-tags", "--quiet"));
        if (properties.getFetchDepth() > 0) {
            fetch.add("--depth");
            fetch.add(Integer.toString(properties.getFetchDepth()));
        }
        fetch.add(cloneUrl);
        // Fetch the ref when the server named one; otherwise ask for the object
        // itself (a source may pin an exact commit sha).
        fetch.add(remote.map(value -> "+" + value.name() + ":" + LOCAL_FETCH_REF).orElse(requested));
        runner.run(mirror, fetch, environment);

        String target = remote.map(RemoteRef::sha).orElse(requested);
        try {
            return new GitCommitRef(revParse(mirror, target, environment), resolvedRef);
        } catch (GitProviderException firstAttempt) {
            // Not every server serves an arbitrary object id by request; a plain
            // fetch of the ref still succeeds.
            runner.run(mirror, List.of("fetch", "--prune", "--no-tags", "--quiet", cloneUrl), environment);
            try {
                return new GitCommitRef(revParse(mirror, target, environment), resolvedRef);
            } catch (GitProviderException secondAttempt) {
                throw new DomainBadRequestException("error.gitSource.commitNotFound", requested);
            }
        }
    }

    /**
     * Fails with an actionable message before git gets a chance to answer
     * {@code fatal: couldn't find remote ref <name>}, which reads like a server
     * fault even though it only means the configured branch does not exist.
     */
    private void requireRefExists(Path mirror, String cloneUrl, String requested,
                                  boolean followDefault, Map<String, String> environment) {
        if (followDefault) {
            throw new DomainBadRequestException("error.gitSource.emptyRepository");
        }
        if (isCommitId(requested)) {
            return;
        }
        List<String> branches = availableBranches(mirror, cloneUrl, environment);
        throw new GitRefNotFoundException(requested, branches, suggestRefName(requested, branches));
    }

    /** Branch names advertised by the server, for the error message of a bad ref. */
    public List<String> availableBranches(Path mirror, String cloneUrl, Map<String, String> environment) {
        try {
            return parseBranchNames(runner.runForText(mirror, List.of("ls-remote", "--heads", cloneUrl), environment));
        } catch (GitProviderException ex) {
            // Listing is best effort: the ref error is the one worth reporting.
            return List.of();
        }
    }

    /** Branch names advertised by the server, with an explicit timeout. */
    public List<String> availableBranches(Path mirror, String cloneUrl, Map<String, String> environment,
                                          Duration timeout) {
        try {
            return parseBranchNames(
                    runner.runForText(mirror, List.of("ls-remote", "--heads", cloneUrl), environment, timeout));
        } catch (GitProviderException ex) {
            return List.of();
        }
    }

    private static List<String> parseBranchNames(String output) {
        List<String> names = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            names.add(shortRefName(line.substring(tab + 1).trim()));
            if (names.size() == MAX_REPORTED_BRANCHES) {
                names.add("...");
                break;
            }
        }
        return List.copyOf(names);
    }

    /**
     * The configured ref's closest neighbour, so a typo becomes "did you mean main?"
     * instead of a bare "does not exist".
     *
     * @return the closest branch when it is close enough to be a probable typo,
     *         otherwise {@code null}
     */
    static String suggestRefName(String ref, List<String> candidates) {
        String normalized = ref.toLowerCase(Locale.ROOT);
        int allowedDistance = Math.max(2, normalized.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || "...".equals(candidate)) {
                continue;
            }
            int distance = editDistance(normalized, candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= allowedDistance ? best : null;
    }

    /** Levenshtein distance; two short ref names, so the quadratic cost is noise. */
    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /** True for a short or full commit id, which git can be asked to fetch directly. */
    static boolean isCommitId(String ref) {
        return ref.matches("(?i)[0-9a-f]{7,64}");
    }

    /** {@code refs/heads/x} and {@code refs/tags/x} read better as {@code x}. */
    static String shortRefName(String name) {
        String value = name.endsWith("^{}") ? name.substring(0, name.length() - 3) : name;
        if (value.startsWith(HEADS_PREFIX)) {
            return value.substring(HEADS_PREFIX.length());
        }
        if (value.startsWith(TAGS_PREFIX)) {
            return value.substring(TAGS_PREFIX.length());
        }
        return value;
    }

    /**
     * Asks the server which branch {@code HEAD} points at.
     *
     * <p>{@code --symref} makes git print {@code ref: refs/heads/<branch> HEAD}
     * next to the commit, so both the default branch name and its commit come from
     * a single round trip.
     *
     * @return the default branch and its commit, or empty when the repository has
     *         no commits yet
     */
    public Optional<RemoteRef> lsRemoteDefaultBranch(Path mirror, String cloneUrl, Map<String, String> environment) {
        return parseDefaultBranch(
                runner.runForText(mirror, List.of("ls-remote", "--symref", cloneUrl, HEAD_REF), environment));
    }

    /** Same as above, with an explicit timeout for cheap on-save checks. */
    public Optional<RemoteRef> lsRemoteDefaultBranch(Path mirror, String cloneUrl, Map<String, String> environment,
                                                     Duration timeout) {
        return parseDefaultBranch(runner.runForText(
                mirror, List.of("ls-remote", "--symref", cloneUrl, HEAD_REF), environment, timeout));
    }

    private static Optional<RemoteRef> parseDefaultBranch(String output) {
        String name = HEAD_REF;
        String sha = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith(SYMREF_PREFIX)) {
                String target = trimmed.substring(SYMREF_PREFIX.length());
                int tab = target.indexOf('\t');
                name = (tab > 0 ? target.substring(0, tab) : target).trim();
                continue;
            }
            int tab = trimmed.indexOf('\t');
            if (tab > 0) {
                sha = trimmed.substring(0, tab).trim();
            }
        }
        return sha == null ? Optional.empty() : Optional.of(new RemoteRef(sha, name));
    }

    /**
     * Asks the server for a ref without downloading objects.
     *
     * @return the matching ref, or empty when the ref does not exist remotely
     */
    public Optional<RemoteRef> lsRemote(Path mirror, String cloneUrl, String ref, Map<String, String> environment) {
        return parseLsRemote(runner.runForText(mirror, List.of("ls-remote", cloneUrl, ref), environment));
    }

    /** Same as above, with an explicit timeout for cheap on-save checks. */
    public Optional<RemoteRef> lsRemote(Path mirror, String cloneUrl, String ref, Map<String, String> environment,
                                        Duration timeout) {
        return parseLsRemote(runner.runForText(mirror, List.of("ls-remote", cloneUrl, ref), environment, timeout));
    }

    private static Optional<RemoteRef> parseLsRemote(String output) {
        RemoteRef match = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String name = trimmed.substring(tab + 1).trim();
            RemoteRef candidate = new RemoteRef(trimmed.substring(0, tab), name);
            if (name.endsWith("^{}")) {
                // Peeled tag: the commit wins over the tag object it points at.
                return Optional.of(candidate);
            }
            if (match == null) {
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    /** True when the mirror already holds the commit. */
    public boolean hasCommit(Path mirror, String commitSha) {
        return hasObject(mirror, commitSha == null ? null : commitSha + "^{commit}");
    }

    /** True when the mirror already holds the blob. */
    public boolean hasBlob(Path mirror, String blobSha) {
        return hasObject(mirror, blobSha);
    }

    private boolean hasObject(Path mirror, String object) {
        if (object == null || object.isBlank()) {
            return false;
        }
        try {
            runner.run(mirror, List.of("cat-file", "-e", object), Map.of());
            return true;
        } catch (GitProviderException ex) {
            return false;
        }
    }

    /** The command runner, so the fetcher can read objects from the mirror. */
    GitCommandRunner runner() {
        return runner;
    }

    /** Removes the mirror of a deleted source. */
    /**
     * Bytes the mirror occupies, or 0 when it does not exist yet.
     */
    public long size(Long sourceId) {
        Path mirror = mirrorPath(sourceId);
        if (!Files.exists(mirror)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(mirror)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ex) {
                    return 0;
                }
            }).sum();
        } catch (IOException ex) {
            return 0;
        }
    }

    public void delete(Long sourceId) {
        Path mirror = mirrorPath(sourceId);
        if (!Files.exists(mirror)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(mirror)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("Could not delete {}: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("Could not delete git mirror {}: {}", mirror, ex.getMessage());
        }
    }

    /** Timestamp of the last fetch, for diagnostics; empty when never fetched. */
    public Optional<Instant> lastFetch(Long sourceId) {
        Path head = mirrorPath(sourceId).resolve("FETCH_HEAD");
        if (!Files.isRegularFile(head)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochMilli(Files.getLastModifiedTime(head).toMillis()));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * Environment carrying credentials into git without persisting them: HTTPS
     * credentials travel in {@code GIT_CONFIG_*} variables (so nothing lands in
     * {@code .git/config}), SSH keys are materialised as an owner-only file that
     * only the ssh command knows about.
     */
    Map<String, String> credentialEnvironment(GitCredential credential) {
        Map<String, String> environment = new HashMap<>();
        if (credential == null || credential.isAnonymous()) {
            return environment;
        }
        if (credential.kind() == GitCredential.Kind.SSH) {
            Path kg = sshKeyFile(credential);
            environment.put("GIT_SSH_COMMAND", "ssh -i " + kg.toAbsolutePath()
                    + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o BatchMode=yes"
                    + " -o UserKnownHostsFile=" + kg.getParent().resolve("known_hosts").toAbsolutePath());
            return environment;
        }
        String plain = (credential.username() == null ? "" : credential.username()) + ":" + credential.secret();
        String header = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        environment.put("GIT_CONFIG_COUNT", "1");
        environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
        environment.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + header);
        return environment;
    }

    /**
     * Writes a pasted private key to a file only the runtime user can read. It is
     * refreshed on every fetch because the credential, not the file, is the source
     * of truth.
     */
    private Path sshKeyFile(GitCredential credential) {
        String privateKey = credential.privateKey();
        if (privateKey == null || privateKey.isBlank()) {
            throw new GitProviderException("SSH credential has no private key");
        }
        Path directory = properties.getWorkDir().resolve("ssh");
        Path keyFile = directory.resolve("id_skillhub");
        try {
            Files.createDirectories(directory);
            Files.writeString(keyFile, privateKey.endsWith("\n") ? privateKey : privateKey + "\n",
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(keyFile,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return keyFile;
        } catch (IOException | UnsupportedOperationException ex) {
            throw new GitProviderException("Could not write the SSH key to " + keyFile + ": " + ex.getMessage(), ex);
        }
    }

    private String revParse(Path mirror, String ref, Map<String, String> environment) {
        String resolved = runner.runForText(mirror, List.of("rev-parse", "--verify", ref + "^{commit}"), environment)
                .trim();
        if (resolved.isEmpty()) {
            throw new GitProviderException("git could not resolve " + ref + " to a commit");
        }
        return resolved;
    }

    private Path ensureMirror(Long sourceId) {
        Path mirror = mirrorPath(sourceId);
        if (Files.isDirectory(mirror.resolve("objects"))) {
            return mirror;
        }
        try {
            Files.createDirectories(mirror);
            runner.run(mirror, List.of("init", "--bare", "--quiet", "."), Map.of());
            return mirror;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not create git mirror at " + mirror, ex);
        }
    }
}
