package com.iflytek.skillhub.gitsync.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iflytek.skillhub.domain.skill.validation.SkillPackagePolicy;
import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitSkillFilePolicyTest {

    private static GitTreeEntry blob(String path, String sha, long size) {
        return new GitTreeEntry(path, "blob", sha, size);
    }

    @Test
    void selectsFilesRelativeToTheSkillDirectory() {
        GitFileSelection selection = GitSkillFilePolicy.select(List.of(
                blob("skills/pdf/SKILL.md", "s1", 100),
                blob("skills/pdf/scripts/run.py", "s2", 200),
                new GitTreeEntry("skills/pdf/scripts", "tree", "t1", 0)
        ), "skills/pdf");

        assertEquals(List.of("SKILL.md", "scripts/run.py"),
                selection.selected().stream().map(GitTreeFile::packagePath).toList());
        assertTrue(selection.skipped().isEmpty());
    }

    @Test
    void rootLevelSkillKeepsPlainPackagePaths() {
        GitFileSelection selection = GitSkillFilePolicy.select(List.of(
                blob("SKILL.md", "s1", 100),
                blob("reference.md", "s2", 100)
        ), "");

        assertEquals(List.of("SKILL.md", "reference.md"),
                selection.selected().stream().map(GitTreeFile::packagePath).toList());
    }

    @Test
    void skipsFilesTheRegistryWouldRejectInsteadOfFailingTheDirectory() {
        GitFileSelection selection = GitSkillFilePolicy.select(List.of(
                blob("skills/pdf/SKILL.md", "s1", 100),
                blob("skills/pdf/LICENSE", "s2", 100),
                blob("skills/pdf/uv.lock", "s3", 100),
                blob("skills/pdf/huge.md", "s4", SkillPackagePolicy.MAX_SINGLE_FILE_SIZE + 1)
        ), "skills/pdf");

        assertEquals(List.of("SKILL.md"),
                selection.selected().stream().map(GitTreeFile::packagePath).toList());
        assertEquals(List.of("LICENSE", "uv.lock", "huge.md"),
                selection.skipped().stream().map(SkippedGitFile::path).toList());
        assertEquals("gitsync.skip.fileType", selection.skipped().getFirst().reasonCode());
        assertEquals("gitsync.skip.tooLarge", selection.skipped().get(2).reasonCode());
        assertEquals(List.of(SkillPackagePolicy.MAX_SINGLE_FILE_SIZE),
                selection.skipped().get(2).reasonArgs());
    }

    @Test
    void rejectsDirectoriesThatExceedHardLimits() {
        List<GitTreeEntry> manyFiles = new ArrayList<>();
        manyFiles.add(blob("skills/pdf/SKILL.md", "s0", 10));
        for (int index = 0; index < SkillPackagePolicy.MAX_FILE_COUNT + 5; index++) {
            manyFiles.add(blob("skills/pdf/file" + index + ".md", "s" + index, 10));
        }
        GitFileSelection tooMany = GitSkillFilePolicy.select(manyFiles, "skills/pdf");
        GitSkillImportException tooManyFailure = assertThrows(GitSkillImportException.class,
                () -> GitSkillFilePolicy.assertWithinLimits(tooMany));
        assertTrue(tooManyFailure.getMessage().contains("importable files"));

        List<GitTreeEntry> largeTotal = new ArrayList<>();
        largeTotal.add(blob("skills/pdf/SKILL.md", "s0", 10));
        for (int index = 0; index < 11; index++) {
            largeTotal.add(blob("skills/pdf/part" + index + ".md", "s" + index,
                    SkillPackagePolicy.MAX_SINGLE_FILE_SIZE));
        }
        GitFileSelection tooLarge = GitSkillFilePolicy.select(largeTotal, "skills/pdf");
        GitSkillImportException tooLargeFailure = assertThrows(GitSkillImportException.class,
                () -> GitSkillFilePolicy.assertWithinLimits(tooLarge));
        assertTrue(tooLargeFailure.getMessage().contains("bytes"));
    }

    @Test
    void rejectsDirectoriesWithoutSkillDocument() {
        GitFileSelection selection = GitSkillFilePolicy.select(List.of(
                blob("skills/pdf/reference.md", "s1", 10)
        ), "skills/pdf");

        GitSkillImportException failure = assertThrows(GitSkillImportException.class,
                () -> GitSkillFilePolicy.assertWithinLimits(selection));

        assertTrue(failure.getMessage().contains("SKILL.md"));
    }

    @Test
    void contentChecksRejectMismatchedBinaryFiles() {
        assertNull(GitSkillFilePolicy.contentProblem("SKILL.md", "# hello".getBytes(StandardCharsets.UTF_8)));
        assertNull(GitSkillFilePolicy.contentProblem("logo.png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}));
        assertTrue(GitSkillFilePolicy.contentProblem("logo.png", "not an image".getBytes(StandardCharsets.UTF_8))
                .contains("does not match extension"));
    }
}
