package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;
import java.util.List;

/** POST /api/v1/search response body. */
public record SearchResponse(
        String query,
        LocalDate asOf,
        String chunkStrategy,
        String strategy,
        long tookMs,
        List<SearchResultItem> results,
        List<Object> conflicts) {
}
