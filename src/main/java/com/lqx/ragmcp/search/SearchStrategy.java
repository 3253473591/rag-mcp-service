package com.lqx.ragmcp.search;

import java.util.Locale;
import java.util.Optional;

/** Retrieval route selected by the {@code strategy} request field. */
public enum SearchStrategy {

    HYBRID("hybrid"),
    VECTOR("vector"),
    KEYWORD("keyword");

    private final String apiValue;

    SearchStrategy(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public boolean usesVector() {
        return this != KEYWORD;
    }

    public boolean usesKeyword() {
        return this != VECTOR;
    }

    public static Optional<SearchStrategy> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SearchStrategy strategy : values()) {
            if (strategy.apiValue.equals(normalized)) {
                return Optional.of(strategy);
            }
        }
        return Optional.empty();
    }
}
