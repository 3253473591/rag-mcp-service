package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.api.error.ApiException;

import java.util.Locale;
import java.util.Optional;

/**
 * Chunking strategies are stored lowercase in kb_chunk.chunk_strategy
 * (CHECK IN ('fixed','heading')).
 */
public enum ChunkStrategy {

    FIXED("fixed"),
    HEADING("heading");

    private final String dbValue;

    ChunkStrategy(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Optional<ChunkStrategy> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ChunkStrategy strategy : values()) {
            if (strategy.dbValue.equals(normalized)) {
                return Optional.of(strategy);
            }
        }
        return Optional.empty();
    }

    public static ChunkStrategy require(String value) {
        return from(value).orElseThrow(() -> ApiException.validation(
                "chunkStrategies 只允许 fixed / heading"));
    }
}
