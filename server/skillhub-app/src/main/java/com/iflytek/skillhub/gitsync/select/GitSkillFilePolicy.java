package com.iflytek.skillhub.gitsync.select;

import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import com.iflytek.skillhub.gitsync.scan.GitSkillDiscovery;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the registry's package policy to a git skill directory.
 *
 * <p>A repository is not an uploaded archive: it routinely contains files the
 * registry does not accept (a bare {@code LICENSE}, lock files, notebooks,
 * oversized samples). Rather than failing the whole directory, those files are
 * skipped and reported, so the dry-run plan shows exactly what would be left
 * behind. Limits that cannot be worked around — too many files, or a package
 * larger than the registry accepts — fail the directory instead of silently
 * truncating it.
 */
public final class GitSkillFilePolicy {

    private GitSkillFilePolicy() {
    }

    /**
     * Selects the importable blobs of one skill directory.
     *
     * @param itemPath skill directory; empty string for the repository root
     */
    public static GitFileSelection select(List<GitTreeEntry> files, String itemPath) {
        List<GitTreeFile> selected = new ArrayList<>();
        List<SkippedGitFile> skipped = new ArrayList<>();

        for (GitTreeEntry file : files) {
            if (!file.isBlob()) {
                continue;
            }
            String packagePath = GitSkillDiscovery.relativePath(itemPath, file.path());
            String invalidPath = invalidPathReason(packagePath);
            if (invalidPath != null) {
                skipped.add(new SkippedGitFile(packagePath, invalidPath));
                continue;
            }
            if (!SkillPackagePolicy.hasAllowedExtension(packagePath)) {
                skipped.add(new SkippedGitFile(packagePath, "file type is not accepted by the registry"));
                continue;
            }
            if (file.size() > SkillPackagePolicy.MAX_SINGLE_FILE_SIZE) {
                skipped.add(new SkippedGitFile(packagePath, "file exceeds "
                        + SkillPackagePolicy.MAX_SINGLE_FILE_SIZE + " bytes"));
                continue;
            }
            selected.add(new GitTreeFile(packagePath, file));
        }

        return new GitFileSelection(List.copyOf(selected), List.copyOf(skipped));
    }

    /**
     * Verifies that the selection can be published at all.
     *
     * @throws GitSkillImportException when the package exceeds hard limits
     */
    public static void assertWithinLimits(GitFileSelection selection) {
        long totalSize = selection.selected().stream().mapToLong(file -> file.entry().size()).sum();
        if (selection.selected().size() > SkillPackagePolicy.MAX_FILE_COUNT) {
            throw new GitSkillImportException("directory has " + selection.selected().size()
                    + " importable files, the registry accepts at most " + SkillPackagePolicy.MAX_FILE_COUNT);
        }
        if (totalSize > SkillPackagePolicy.MAX_TOTAL_PACKAGE_SIZE) {
            throw new GitSkillImportException("directory is " + totalSize
                    + " bytes, the registry accepts at most " + SkillPackagePolicy.MAX_TOTAL_PACKAGE_SIZE);
        }
        if (selection.selected().stream().noneMatch(file -> SkillPackagePolicy.SKILL_MD_PATH.equals(file.packagePath()))) {
            throw new GitSkillImportException("directory has no SKILL.md at its root");
        }
    }

    /**
     * Applies the registry's content/extension checks to downloaded bytes.
     *
     * @return {@code null} when the file is acceptable, otherwise the reason
     */
    public static String contentProblem(String packagePath, byte[] content) {
        return SkillPackagePolicy.validateContentMatchesExtension(packagePath, content);
    }

    /**
     * Paths that cannot be represented as a package entry are skipped; the
     * registry would reject the whole package for them.
     */
    private static String invalidPathReason(String packagePath) {
        try {
            SkillPackagePolicy.normalizeEntryPath(packagePath);
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }
}
