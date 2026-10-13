package com.iflytek.skillhub.gitsync.git;

import java.util.List;

/**
 * Read-only access to a git repository.
 *
 * <p>Implementations must never mutate the remote repository. The registry holds
 * exactly one implementation — git itself — so any server that speaks standard
 * git-over-HTTP(S) or SSH can be synced without a provider-specific adapter.
 */
public interface GitRepositoryFetcher {

    /**
     * Resolves a branch, tag, or commit-ish to an immutable commit SHA, fetching
     * the repository content when the local mirror does not hold it yet.
     */
    GitCommitRef resolveRef(GitRepositoryRef repository, String ref);

    /**
     * Lists the complete recursive tree of a commit.
     */
    List<GitTreeEntry> listTree(GitRepositoryRef repository, String commitSha);

    /**
     * Reads a text file at the given commit.
     */
    String readFile(GitRepositoryRef repository, String commitSha, String path);

    /**
     * Reads a blob by its tree SHA, preserving binary content.
     *
     * <p>Used by the sync executor: blob access works for binary files and for
     * files larger than any single-file API limit.
     */
    byte[] readBlob(GitRepositoryRef repository, String blobSha);
}
