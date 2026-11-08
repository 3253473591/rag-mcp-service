package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * POST /api/v1/work-items request body (docs/02-api-contract.md 2.5).
 *
 * <p>{@code requestedDocIds == null} means "all documents"; {@code []} means "none". Fields are
 * nullable here because defaults and bounds are validated in {@code WorkItemService} so failures
 * use {@code ERR-VALIDATION} with field details.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateWorkItemRequest(
        String requester,
        String purpose,
        List<String> requestedTools,
        List<String> requestedDocIds,
        Integer requestedTopK) {
}
