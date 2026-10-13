package com.iflytek.skillhub.gitsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GitSkillDocumentVersionTest {

    private static String document(String frontmatter) {
        return "---\n" + frontmatter + "\n---\n\n# Body\n";
    }

    @Test
    void readsDeclaredVersion() {
        assertEquals("1.2.3", GitSkillDocumentVersion.readVersion(document("name: PDF\ndescription: d\nversion: 1.2.3")));
        assertEquals("1.2.3", GitSkillDocumentVersion.readVersion(document("name: PDF\nversion: \"1.2.3\"")));
        assertEquals("1.2.3", GitSkillDocumentVersion.readVersion(document("name: PDF\nversion: '1.2.3'")));
    }

    @Test
    void readsNoVersionWhenAbsentOrDocumentIsBroken() {
        assertNull(GitSkillDocumentVersion.readVersion(document("name: PDF\ndescription: d")));
        assertNull(GitSkillDocumentVersion.readVersion("no frontmatter at all"));
        assertNull(GitSkillDocumentVersion.readVersion(null));
    }

    @Test
    void replacesExistingVersionAndKeepsTheRestOfTheDocument() {
        byte[] original = document("name: PDF\ndescription: d\nversion: 1.0.0\nlicense: MIT")
                .getBytes(StandardCharsets.UTF_8);

        String rewritten = new String(GitSkillDocumentVersion.withVersion(original, "1.0.0-abc12345"),
                StandardCharsets.UTF_8);

        assertEquals("1.0.0-abc12345", GitSkillDocumentVersion.readVersion(rewritten));
        assertTrue(rewritten.contains("license: MIT"));
        assertTrue(rewritten.contains("# Body"));
        assertEquals(1, rewritten.split("version:", -1).length - 1);
    }

    @Test
    void insertsVersionWhenTheDocumentDoesNotDeclareOne() {
        byte[] rewritten = GitSkillDocumentVersion.withVersion(
                document("name: PDF\ndescription: d").getBytes(StandardCharsets.UTF_8), "0.0.0-abc12345");

        assertEquals("0.0.0-abc12345", GitSkillDocumentVersion.readVersion(
                new String(rewritten, StandardCharsets.UTF_8)));
    }

    @Test
    void returnsTheSameArrayWhenThereIsNothingToRewrite() {
        byte[] original = "no frontmatter".getBytes(StandardCharsets.UTF_8);

        assertSame(original, GitSkillDocumentVersion.withVersion(original, "1.0.0-abc12345"));
    }

    @Test
    void preservesCarriageReturnsAndReportsNoChangeForIdenticalVersion() {
        String crlf = "---\r\nname: PDF\r\ndescription: d\r\nversion: 1.0.0\r\n---\r\n# Body\r\n";
        byte[] rewritten = GitSkillDocumentVersion.withVersion(crlf.getBytes(StandardCharsets.UTF_8), "2.0.0");

        assertArrayEquals(("---\r\nname: PDF\r\ndescription: d\r\nversion: 2.0.0\r\n---\r\n# Body\r\n")
                .getBytes(StandardCharsets.UTF_8), rewritten);
    }

    @Test
    void derivesDeterministicPerCommitVersion() {
        assertEquals("1.0.0-abc12345", GitSkillDocumentVersion.derivedVersion("1.0.0", "ABC12345deadbeef"));
        assertEquals("0.0.0-abc12345", GitSkillDocumentVersion.derivedVersion(null, "abc12345deadbeef"));
        assertEquals("0.0.0-abc12345", GitSkillDocumentVersion.derivedVersion("  ", "abc12345deadbeef"));
        assertEquals("1.0.0-abc12345", GitSkillDocumentVersion.derivedVersion("1.0.0", "abc12345"));
        assertEquals("1.0.0-unknown", GitSkillDocumentVersion.derivedVersion("1.0.0", null));
    }
}
