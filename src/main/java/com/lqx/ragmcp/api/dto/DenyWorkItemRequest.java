package com.lqx.ragmcp.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /api/v1/work-items/{id}/deny request body (docs/02-api-contract.md 2.8). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DenyWorkItemRequest(String reason) {
}
