package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** POST /api/v1/documents response body. */
public record UploadResponse(
        String docId,
        String docKey,
        String version,
        String title,
        String sourceType,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        List<String> chunkStrategies,
        int chunkCount,
        Map<String, Integer> chunkCounts,
        boolean duplicate,
        long tookMs) {
}
