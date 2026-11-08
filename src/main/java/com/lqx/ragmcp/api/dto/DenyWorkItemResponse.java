package com.lqx.ragmcp.api.dto;

import java.time.OffsetDateTime;

/** 200 response for POST /api/v1/work-items/{id}/deny. */
public record DenyWorkItemResponse(String workItemId, String status, OffsetDateTime decidedAt) {
}
