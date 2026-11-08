package com.lqx.ragmcp.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clause id extraction (docs/11-retrieval-and-eval-spec.md 1.6):
 * {@code ^(\d+(?:\.\d+)*)(?:\s|、|\.)}. When there is no number, the clause id is null -
 * clause ids are never invented.
 */
public final class ClauseIds {

    private static final Pattern CLAUSE_ID = Pattern.compile("^(\\d+(?:\\.\\d+)*)(?=\\s|、|\\.|$)");

    private ClauseIds() {
    }

    public static String extract(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = CLAUSE_ID.matcher(text.trim());
        return matcher.find() ? matcher.group(1) : null;
    }
}
