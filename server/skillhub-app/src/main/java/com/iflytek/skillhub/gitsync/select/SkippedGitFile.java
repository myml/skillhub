package com.iflytek.skillhub.gitsync.select;

import java.util.List;

/**
 * A repository file that will not be imported, with a localizable reason.
 *
 * <p>The reason travels as a message code plus arguments so the dry-run can render
 * it in the caller's language, exactly like the planned-failure problems.
 */
public record SkippedGitFile(String path, String reasonCode, List<Object> reasonArgs) {

    public SkippedGitFile {
        reasonArgs = reasonArgs == null ? List.of() : List.copyOf(reasonArgs);
    }

    public static SkippedGitFile of(String path, String reasonCode, Object... reasonArgs) {
        return new SkippedGitFile(path, reasonCode, List.of(reasonArgs));
    }
}
