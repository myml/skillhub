package com.iflytek.skillhub.gitsync.scan;

import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a repository tree into the list of skill directories to import.
 *
 * <p>A directory qualifies when it directly contains a {@code SKILL.md}. The
 * directory fingerprint is computed from the sorted {@code relativePath:blobSha}
 * pairs of every file inside it, so any content, rename, addition, or deletion
 * changes the fingerprint. The git commit id is deliberately not used: a commit
 * touching an unrelated skill must not look like a change for this one.
 */
public final class GitSkillDiscovery {

    public static final String SKILL_DOCUMENT = "SKILL.md";

    private GitSkillDiscovery() {
    }

    public static List<DiscoveredSkill> discover(List<GitTreeEntry> tree, String subpath) {
        String prefix = normalizeSubpath(subpath);
        List<GitTreeEntry> blobs = new ArrayList<>();
        for (GitTreeEntry entry : tree) {
            if (entry.isBlob() && withinSubpath(entry.path(), prefix)) {
                blobs.add(entry);
            }
        }
        blobs.sort(Comparator.comparing(GitTreeEntry::path));

        Set<String> candidateDirs = new TreeSet<>();
        for (GitTreeEntry blob : blobs) {
            if (SKILL_DOCUMENT.equals(fileName(blob.path()))) {
                candidateDirs.add(parentDir(blob.path()));
            }
        }

        List<DiscoveredSkill> skills = new ArrayList<>();
        for (String dir : candidateDirs) {
            List<GitTreeEntry> files = new ArrayList<>();
            for (GitTreeEntry blob : blobs) {
                if (isInside(dir, blob.path())) {
                    files.add(blob);
                }
            }
            files.sort(Comparator.comparing(GitTreeEntry::path));
            skills.add(new DiscoveredSkill(dir, treeSha(dir, files), List.copyOf(files)));
        }
        skills.sort(Comparator.comparing(DiscoveredSkill::itemPath));
        return skills;
    }

    public static String treeSha(String dir, List<GitTreeEntry> files) {
        StringBuilder material = new StringBuilder();
        files.stream()
                .sorted(Comparator.comparing(GitTreeEntry::path))
                .forEach(file -> material.append(relativePath(dir, file.path()))
                        .append(':')
                        .append(file.sha())
                        .append('\n'));
        return sha256Hex(material.toString());
    }

    /**
     * Strips the enclosing directory so package entries stay relative to the skill root.
     */
    public static String relativePath(String dir, String path) {
        if (dir == null || dir.isEmpty()) {
            return path;
        }
        String prefix = dir + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    static String normalizeSubpath(String subpath) {
        if (subpath == null) {
            return "";
        }
        String normalized = subpath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean withinSubpath(String path, String prefix) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean isInside(String dir, String path) {
        if (dir.isEmpty()) {
            return true;
        }
        return path.startsWith(dir + "/");
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String sha256Hex(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
