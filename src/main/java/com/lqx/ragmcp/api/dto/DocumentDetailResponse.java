package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record DocumentDetailResponse(
        String docId,
        String docKey,
        String title,
        String sourceType,
        String sourceUri,
        String checksum,
        String version,
        String embeddingModel,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String supersedesDocId,
        String authorityLevel,
        String scope,
        Map<String, Object> metadata,
        int chunkCount,
        Map<String, Integer> chunkCounts,
        List<String> chunkStrategies,
        OffsetDateTime createdAt) {
}
