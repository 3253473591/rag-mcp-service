package com.lqx.ragmcp.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 200 response for GET /api/v1/work-items/{id} (docs/02-api-contract.md 2.6). */
public record WorkItemResponse(
        String workItemId,
        String requester,
        String purpose,
        List<String> requestedTools,
        List<String> requestedDocIds,
        int requestedTopK,
        String status,
        String decidedBy,
        OffsetDateTime decidedAt,
        String decisionReason,
        OffsetDateTime createdAt,
        MandateResponse mandate) {
}
