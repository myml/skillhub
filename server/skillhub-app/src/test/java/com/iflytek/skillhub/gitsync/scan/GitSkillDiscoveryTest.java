package com.iflytek.skillhub.gitsync.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iflytek.skillhub.gitsync.git.GitTreeEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitSkillDiscoveryTest {

    private static GitTreeEntry blob(String path, String sha) {
        return new GitTreeEntry(path, "blob", sha, 10L);
    }

    private static List<GitTreeEntry> tree(GitTreeEntry... entries) {
        return new ArrayList<>(List.of(entries));
    }

    @Test
    void discoversDirectoriesContainingSkillDocument() {
        List<GitTreeEntry> entries = tree(
                blob("README.md", "r1"),
                blob("skills/pdf/SKILL.md", "s1"),
                blob("skills/pdf/scripts/run.py", "p1"),
                blob("skills/excel/SKILL.md", "s2"),
                blob("docs/notes.md", "n1")
        );

        List<DiscoveredSkill> discovered = GitSkillDiscovery.discover(entries, "");

        assertEquals(List.of("skills/excel", "skills/pdf"),
                discovered.stream().map(DiscoveredSkill::itemPath).toList());
    }

    @Test
    void includesRepositoryRootAsSkillWhenSkillDocumentIsAtRoot() {
        List<DiscoveredSkill> discovered = GitSkillDiscovery.discover(
                tree(blob("SKILL.md", "root1"), blob("scripts/a.sh", "a1")), "");

        assertEquals(1, discovered.size());
        assertEquals("", discovered.getFirst().itemPath());
        assertEquals(2, discovered.getFirst().files().size());
    }

    @Test
    void honorsSubpathFilter() {
        List<GitTreeEntry> entries = tree(
                blob("skills/pdf/SKILL.md", "s1"),
                blob("other/excel/SKILL.md", "s2")
        );

        List<DiscoveredSkill> discovered = GitSkillDiscovery.discover(entries, "skills/");

        assertEquals(1, discovered.size());
        assertEquals("skills/pdf", discovered.getFirst().itemPath());
    }

    @Test
    void treeShaIsStableAcrossEntryOrder() {
        List<GitTreeEntry> ordered = tree(
                blob("skills/pdf/SKILL.md", "s1"),
                blob("skills/pdf/references/a.md", "a1")
        );
        List<GitTreeEntry> shuffled = tree(
                blob("skills/pdf/references/a.md", "a1"),
                blob("skills/pdf/SKILL.md", "s1")
        );

        assertEquals(
                GitSkillDiscovery.discover(ordered, "").getFirst().treeSha(),
                GitSkillDiscovery.discover(shuffled, "").getFirst().treeSha()
        );
    }

    @Test
    void treeShaChangesForNestedFileButNotForUnrelatedPaths() {
        String baseline = GitSkillDiscovery.discover(tree(
                blob("skills/pdf/SKILL.md", "s1"),
                blob("skills/pdf/references/a.md", "a1"),
                blob("skills/excel/SKILL.md", "s2")
        ), "").stream().filter(skill -> skill.itemPath().equals("skills/pdf")).findFirst().orElseThrow().treeSha();

        String nestedChanged = GitSkillDiscovery.discover(tree(
                blob("skills/pdf/SKILL.md", "s1"),
                blob("skills/pdf/references/a.md", "a2"),
                blob("skills/excel/SKILL.md", "s3")
        ), "").stream().filter(skill -> skill.itemPath().equals("skills/pdf")).findFirst().orElseThrow().treeSha();

        String unrelatedChanged = GitSkillDiscovery.discover(tree(
                blob("skills/pdf/SKILL.md", "s1"),
                blob("skills/pdf/references/a.md", "a1"),
                blob("skills/excel/SKILL.md", "s9")
        ), "").stream().filter(skill -> skill.itemPath().equals("skills/pdf")).findFirst().orElseThrow().treeSha();

        assertNotEquals(baseline, nestedChanged, "changes inside a nested directory must be detected");
        assertEquals(baseline, unrelatedChanged, "another skill changing must not mark this one as changed");
    }

    @Test
    void ignoresTreesAndDirectoriesWithoutSkillDocument() {
        List<DiscoveredSkill> discovered = GitSkillDiscovery.discover(tree(
                new GitTreeEntry("skills", "tree", "t1", 0L),
                blob("skills/pdf/README.md", "r1")
        ), "");

        assertTrue(discovered.isEmpty());
    }

    @Test
    void relativePathStripsSkillDirectoryPrefix() {
        assertEquals("references/a.md", GitSkillDiscovery.relativePath("skills/pdf", "skills/pdf/references/a.md"));
        assertEquals("SKILL.md", GitSkillDiscovery.relativePath("", "SKILL.md"));
    }

    @Test
    void discoverIsDeterministicForUnsortedInput() {
        List<GitTreeEntry> entries = tree(
                blob("b/SKILL.md", "b1"),
                blob("a/SKILL.md", "a1")
        );

        List<String> first = GitSkillDiscovery.discover(entries, "").stream()
                .map(DiscoveredSkill::itemPath).toList();
        Collections.reverse(entries);
        List<String> second = GitSkillDiscovery.discover(entries, "").stream()
                .map(DiscoveredSkill::itemPath).toList();

        assertEquals(first, second);
        assertEquals(List.of("a", "b"), first);
    }
}
