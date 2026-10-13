package com.iflytek.skillhub.gitsync.git;

/**
 * One entry of a repository tree.
 *
 * @param path repository-relative path, e.g. {@code skills/pdf/SKILL.md}
 * @param type {@code blob} or {@code tree}
 * @param sha  git object id (blob sha for files, tree sha for directories)
 * @param size file size in bytes when known
 */
public record GitTreeEntry(String path, String type, String sha, long size) {

    public boolean isBlob() {
        return "blob".equalsIgnoreCase(type);
    }

    public boolean isTree() {
        return "tree".equalsIgnoreCase(type) || "commit".equalsIgnoreCase(type);
    }
}
