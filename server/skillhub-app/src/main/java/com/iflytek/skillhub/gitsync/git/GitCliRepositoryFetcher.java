package com.iflytek.skillhub.gitsync.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads repositories with the local {@code git} binary through a per-source bare
 * mirror.
 *
 * <p>Replaces the per-provider REST integrations: resolving a ref, listing a
 * tree and reading blobs are all plain git operations, so GitHub, GitLab, Gitea,
 * Forgejo, Bitbucket, a bare repository on an internal host — anything reachable
 * over git — works with the same code and without a provider adapter.
 *
 * <p>Only {@code resolveRef} touches the network (and only when the mirror does
 * not already hold the commit); listing and reading happen on the local mirror.
 */
@Component
public class GitCliRepositoryFetcher implements GitRepositoryFetcher {

    private final GitMirrorStore mirrors;

    public GitCliRepositoryFetcher(GitMirrorStore mirrors) {
        this.mirrors = mirrors;
    }

    @Override
    public GitCommitRef resolveRef(GitRepositoryRef repository, String ref) {
        String effectiveRef = ref == null || ref.isBlank() ? "HEAD" : ref.trim();
        return mirrors.fetch(repository.sourceId(), repository.repoUrl(), effectiveRef, repository.credential());
    }

    @Override
    public List<GitTreeEntry> listTree(GitRepositoryRef repository, String commitSha) {
        Path mirror = requireMirror(repository, commitSha);
        byte[] output = mirrors.runner()
                .run(mirror, List.of("ls-tree", "-r", "-l", "-z", commitSha), GitTreeListing.localOnly());
        return List.copyOf(GitTreeListing.parse(output));
    }

    @Override
    public String readFile(GitRepositoryRef repository, String commitSha, String path) {
        Path mirror = requireMirror(repository, commitSha);
        byte[] content = mirrors.runner()
                .run(mirror, List.of("cat-file", "blob", commitSha + ":" + path), GitTreeListing.localOnly());
        return new String(content, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] readBlob(GitRepositoryRef repository, String blobSha) {
        Path mirror = mirrors.mirrorPath(repository.sourceId());
        if (!mirrors.hasBlob(mirror, blobSha)) {
            throw new GitProviderException("Blob " + blobSha + " is not present in the mirror of source "
                    + repository.sourceId() + "; run a sync first");
        }
        return mirrors.runner().run(mirror, List.of("cat-file", "blob", blobSha), Map.of());
    }

    private Path requireMirror(GitRepositoryRef repository, String commitSha) {
        Path mirror = mirrors.mirrorPath(repository.sourceId());
        if (!mirrors.hasCommit(mirror, commitSha)) {
            throw new GitProviderException("Commit " + commitSha + " is not present in the mirror of source "
                    + repository.sourceId() + "; run a sync first");
        }
        return mirror;
    }
}
