package com.iflytek.skillhub.gitsync;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the {@code version} field of a {@code SKILL.md} frontmatter.
 *
 * <p>Needed because the registry rejects a second publish of an already
 * published version: when a repository changes a skill's content without
 * bumping the version in its document, the sync derives a
 * {@code <declared version>-<short commit>} version instead of failing.
 */
public final class GitSkillDocumentVersion {

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---[ \t]*\\R(?<body>.*?)\\R---[ \t]*(?<rest>\\R.*)?\\z",
            Pattern.DOTALL);
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^version[ \t]*:.*$");
    private static final Pattern VERSION_VALUE = Pattern.compile("(?m)^version[ \t]*:[ \t]*(?<value>.*)$");

    private GitSkillDocumentVersion() {
    }

    /**
     * @return the document with its frontmatter version replaced or added; the
     *         original array is returned when there is no frontmatter to edit
     */
    public static byte[] withVersion(byte[] skillDocument, String version) {
        String document = new String(skillDocument, StandardCharsets.UTF_8);
        String separator = document.contains("\r\n") ? "\r\n" : "\n";
        String rewritten = replaceVersion(document, version, separator);
        return rewritten.equals(document) ? skillDocument : rewritten.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads the declared version from a skill document.
     *
     * @return the declared version, or {@code null} when absent
     */
    public static String readVersion(String skillDocument) {
        if (skillDocument == null) {
            return null;
        }
        Matcher frontmatter = FRONTMATTER.matcher(skillDocument);
        if (!frontmatter.matches()) {
            return null;
        }
        Matcher value = VERSION_VALUE.matcher(frontmatter.group("body"));
        if (!value.find()) {
            return null;
        }
        String declared = value.group("value").trim();
        if (declared.length() >= 2) {
            boolean doubleQuoted = declared.startsWith("\"") && declared.endsWith("\"");
            boolean singleQuoted = declared.startsWith("'") && declared.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                declared = declared.substring(1, declared.length() - 1).trim();
            }
        }
        return declared.isBlank() ? null : declared;
    }

    /**
     * Deterministic version for a repository commit, so re-syncing the same
     * commit is idempotent.
     */
    public static String derivedVersion(String declaredVersion, String commitSha) {
        String base = declaredVersion == null || declaredVersion.isBlank() ? "0.0.0" : declaredVersion.trim();
        String shortSha = commitSha == null || commitSha.isBlank()
                ? "unknown"
                : commitSha.trim().toLowerCase(Locale.ROOT);
        if (shortSha.length() > 8) {
            shortSha = shortSha.substring(0, 8);
        }
        return base + "-" + shortSha;
    }

    private static String replaceVersion(String document, String version, String separator) {
        Matcher frontmatter = FRONTMATTER.matcher(document);
        if (!frontmatter.matches()) {
            return document;
        }
        String body = frontmatter.group("body");
        String replacement = "version: " + version;
        String rewrittenBody = VERSION_LINE.matcher(body).find()
                ? VERSION_LINE.matcher(body).replaceFirst(Matcher.quoteReplacement(replacement))
                : body + separator + replacement;
        String rest = frontmatter.group("rest") == null ? "" : frontmatter.group("rest");
        return "---" + separator + rewrittenBody + separator + "---" + rest;
    }
}
