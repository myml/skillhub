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
     *
     * <p>An empty {@code ref} resolves the repository's default branch as the
     * remote reports it, so a source does not have to know whether that branch is
     * called {@code main}, {@code master} or anything else.
     *
     * @throws com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException
     *         when the ref does not exist in the repository
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
