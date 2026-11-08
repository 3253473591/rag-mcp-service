package com.lqx.ragmcp.api.dto;

/** 200 response for POST /api/v1/work-items/{id}/approve. */
public record ApproveWorkItemResponse(String workItemId, String status, MandateResponse mandate) {
}
