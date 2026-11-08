package com.lqx.ragmcp.search;

/** PostgreSQL LIKE/ILIKE pattern escaping (docs/11-retrieval-and-eval-spec.md 3.2). */
public final class KeywordEscaper {

    private KeywordEscaper() {
    }

    /** Escapes backslash, percent and underscore, then wraps with leading/trailing percent. */
    public static String toPattern(String query) {
        return "%" + escapeLike(query) + "%";
    }

    public static String escapeLike(String query) {
        String value = query == null ? "" : query;
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * Keyword branch uses at most the first {@code maxChars} characters so a very long query
     * does not degrade trigram matching.
     */
    public static String keywordText(String query, int maxChars) {
        String value = query == null ? "" : query;
        int limit = Math.max(1, maxChars);
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
