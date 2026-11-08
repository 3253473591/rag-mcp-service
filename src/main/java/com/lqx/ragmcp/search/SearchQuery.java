package com.lqx.ragmcp.search;

import com.lqx.ragmcp.chunk.ChunkStrategy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Validated, normalized search input used by the service/repository. */
public record SearchQuery(
        String query,
        int topK,
        List<UUID> docIds,
        String version,
        LocalDate asOf,
        boolean includeSuperseded,
        SearchVersionMode versionMode,
        ChunkStrategy chunkStrategy,
        SearchStrategy strategy) {
}
