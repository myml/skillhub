package com.iflytek.skillhub.gitsync.select;

/**
 * Raised when a skill directory cannot be imported at all
 * (too many files, or a package larger than the registry accepts).
 */
public class GitSkillImportException extends RuntimeException {

    public GitSkillImportException(String message) {
        super(message);
    }
}
