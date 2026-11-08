package com.lqx.ragmcp.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.api.dto.DocumentDetailResponse;
import com.lqx.ragmcp.api.dto.DocumentListResponse;
import com.lqx.ragmcp.api.dto.DocumentSummaryResponse;
import com.lqx.ragmcp.api.dto.UploadResponse;
import com.lqx.ragmcp.document.DocumentDetail;
import com.lqx.ragmcp.document.DocumentPage;
import com.lqx.ragmcp.document.DocumentRecord;
import com.lqx.ragmcp.document.UploadResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps domain records to API response bodies (docs/02-api-contract.md 2.1-2.3). */
public final class DocumentApiMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private DocumentApiMapper() {
    }

    public static UploadResponse toUploadResponse(UploadResult result) {
        DocumentRecord document = result.document();
        Map<String, Integer> counts = new LinkedHashMap<>();
        result.chunkCounts().forEach((strategy, count) -> counts.put(strategy.dbValue(), count));
        List<String> strategies = new ArrayList<>(counts.keySet());
        return new UploadResponse(
                document.id().toString(),
                document.docKey(),
                document.version(),
                document.title(),
                document.sourceType(),
                document.status(),
                document.effectiveFrom(),
                document.effectiveTo(),
                strategies,
                result.totalChunks(),
                counts,
                result.duplicate(),
                result.tookMs());
    }

    public static DocumentListResponse toListResponse(DocumentPage page) {
        List<DocumentSummaryResponse> items = page.items().stream()
                .map(row -> {
                    DocumentRecord document = row.document();
                    return new DocumentSummaryResponse(
                            document.id().toString(),
                            document.docKey(),
                            document.title(),
                            document.sourceType(),
                            document.version(),
                            document.status(),
                            document.effectiveFrom(),
                            document.effectiveTo(),
                            row.chunkCount(),
                            document.createdAt());
                })
                .toList();
        return new DocumentListResponse(items, page.total(), page.limit(), page.offset());
    }

    public static DocumentDetailResponse toDetailResponse(DocumentDetail detail, ObjectMapper objectMapper) {
        DocumentRecord document = detail.document();
        Map<String, Integer> counts = new LinkedHashMap<>();
        detail.chunkCounts().forEach((strategy, count) -> counts.put(strategy.dbValue(), count));
        return new DocumentDetailResponse(
                document.id().toString(),
                document.docKey(),
                document.title(),
                document.sourceType(),
                document.sourceUri(),
                document.checksum(),
                document.version(),
                document.embeddingModel(),
                document.status(),
                document.effectiveFrom(),
                document.effectiveTo(),
                document.supersedesDocId() == null ? null : document.supersedesDocId().toString(),
                document.authorityLevel(),
                document.scope(),
                parseMetadata(document.metadataJson(), objectMapper),
                detail.totalChunks(),
                counts,
                new ArrayList<>(counts.keySet()),
                document.createdAt());
    }

    private static Map<String, Object> parseMetadata(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
