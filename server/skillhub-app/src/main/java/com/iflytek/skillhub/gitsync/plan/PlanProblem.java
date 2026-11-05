package com.iflytek.skillhub.gitsync.plan;

import java.util.List;

/**
 * A reason the next sync would fail to import one directory.
 *
 * <p>Carries the message code and its arguments rather than prose, so the plan
 * can be rendered in the caller's language and so the wording matches the
 * failure a real sync would report for the same condition.
 *
 * @param code message key, normally one the publish pipeline itself raises
 * @param args arguments for that key
 */
public record PlanProblem(String code, List<Object> args) {

    public PlanProblem {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static PlanProblem of(String code, Object... args) {
        return new PlanProblem(code, args == null ? List.of() : List.of(args));
    }
}