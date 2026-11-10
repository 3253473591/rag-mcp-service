package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/pages/search request body. Deliberately a subset of {@link SearchRequest}: the
 * page route is vector-only, so {@code strategy} / {@code chunkStrategy} / {@code detectConflicts}
 * do not apply. Validation and defaults are delegated to {@code SearchRequestValidator}, so the
 * error codes are the same as POST /api/v1/search.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageSearchRequest(
        String query,
        Integer topK,
        List<String> docIds,
        String version,
        String asOf,
        Boolean includeSuperseded) {
}
