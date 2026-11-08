package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/search request body. See docs/02-api-contract.md 2.9.
 *
 * <p>All fields are nullable on purpose: defaults and bounds are validated centrally by
 * {@code SearchRequestValidator} so the error code can be {@code ERR-VALIDATION} or
 * {@code ERR-INVALID_AS_OF} as the contract requires.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchRequest(
        String query,
        Integer topK,
        List<String> docIds,
        String version,
        String asOf,
        Boolean includeSuperseded,
        String chunkStrategy,
        String strategy,
        Boolean detectConflicts) {
}
