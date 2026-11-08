package com.lqx.ragmcp.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Approved mandate view (docs/02-api-contract.md 2.6/2.7). */
public record MandateResponse(
        String mandateId,
        List<String> allowedTools,
        List<String> allowedDocIds,
        int maxTopK,
        OffsetDateTime expiresAt) {
}
