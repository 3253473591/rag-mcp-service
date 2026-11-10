package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/search response body.
 *
 * <p>Phase 7 adds abstention fields additively: {@code abstained}, {@code abstainReason},
 * {@code message} and {@code diagnostics}. The non-abstained path keeps the original semantics,
 * field order and result ordering; old clients can ignore the new fields. When
 * {@code abstained == true}, {@code results} is always empty and {@code message} is a
 * system-owned, non-blank Chinese explanation.</p>
 */
public record SearchResponse(
        String query,
        LocalDate asOf,
        String chunkStrategy,
        String strategy,
        long tookMs,
        List<SearchResultItem> results,
        List<Object> conflicts,
        boolean abstained,
        String abstainReason,
        String message,
        AbstainDiagnostics diagnostics) {
}
