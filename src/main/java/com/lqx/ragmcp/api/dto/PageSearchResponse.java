package com.lqx.ragmcp.api.dto;

import java.util.List;

/** POST /api/v1/pages/search response. {@code results} is ordered by cosine score, descending. */
public record PageSearchResponse(
        String query,
        String asOf,
        int topK,
        long tookMs,
        List<PageSearchResult> results) {
}
