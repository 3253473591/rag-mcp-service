package com.lqx.ragmcp.api.dto;

import java.time.OffsetDateTime;

/** 201 response for POST /api/v1/work-items. */
public record CreateWorkItemResponse(String workItemId, String status, OffsetDateTime createdAt) {
}
