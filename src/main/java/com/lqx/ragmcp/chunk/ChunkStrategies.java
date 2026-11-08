package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.api.error.ApiException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the multipart {@code chunkStrategies} parameter:
 * default ["heading"], accepts "fixed,heading" or repeated parameters, lowercases,
 * de-duplicates and allows at most two strategies.
 */
public final class ChunkStrategies {

    private ChunkStrategies() {
    }

    public static List<ChunkStrategy> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(ChunkStrategy.HEADING);
        }
        Set<ChunkStrategy> strategies = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                strategies.add(ChunkStrategy.require(trimmed));
            }
        }
        if (strategies.isEmpty()) {
            return List.of(ChunkStrategy.HEADING);
        }
        if (strategies.size() > 2) {
            throw ApiException.validation("chunkStrategies 最多 2 个");
        }
        return List.copyOf(strategies);
    }
}
