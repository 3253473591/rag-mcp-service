package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DocumentSummaryResponse(
        String docId,
        String docKey,
        String title,
        String sourceType,
        String version,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int chunkCount,
        OffsetDateTime createdAt) {
}
