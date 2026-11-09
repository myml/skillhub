package com.iflytek.skillhub.gitsync.git;

import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configured branch, tag or commit does not exist in the repository.
 *
 * <p>Carries the refs the server actually advertises (and a "did you mean" guess
 * when one is close by), so the API can answer "wrong branch, here are the right
 * ones" instead of a generic server error. It is a caller/config problem, hence a
 * 400 rather than a 5xx: nothing is broken on our side.
 */
public class GitRefNotFoundException extends LocalizedDomainException {

    private final String ref;
    private final List<String> availableBranches;
    private final String suggestion;

    public GitRefNotFoundException(String ref, List<String> availableBranches, String suggestion) {
        super(messageCode(availableBranches, suggestion), messageArgs(ref, availableBranches, suggestion));
        this.ref = ref;
        this.availableBranches = List.copyOf(availableBranches);
        this.suggestion = suggestion;
    }

    /** The ref the administrator configured. */
    public String ref() {
        return ref;
    }

    /** Branch names the repository advertises, as reported by the server. */
    public List<String> availableBranches() {
        return availableBranches;
    }

    /** A branch close to the configured one, or {@code null} when none is close. */
    public String suggestion() {
        return suggestion;
    }

    /**
     * Structured payload for the API envelope, so a client can render the
     * alternatives instead of parsing them out of the message.
     */
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ref", ref);
        details.put("availableBranches", availableBranches);
        if (suggestion != null) {
            details.put("suggestion", suggestion);
        }
        return details;
    }

    @Override
    public int statusCode() {
        return 400;
    }

    private static String messageCode(List<String> availableBranches, String suggestion) {
        if (availableBranches.isEmpty()) {
            return "error.gitSource.refNotFoundNoBranches";
        }
        return suggestion == null ? "error.gitSource.refNotFound" : "error.gitSource.refNotFoundWithSuggestion";
    }

    private static Object[] messageArgs(String ref, List<String> availableBranches, String suggestion) {
        if (availableBranches.isEmpty()) {
            return new Object[] {ref};
        }
        String branches = String.join(", ", availableBranches);
        return suggestion == null
                ? new Object[] {ref, branches}
                : new Object[] {ref, suggestion, branches};
    }
}
