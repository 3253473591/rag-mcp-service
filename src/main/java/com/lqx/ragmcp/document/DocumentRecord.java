package com.lqx.ragmcp.document;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Row representation of kb_document. */
public record DocumentRecord(
        UUID id,
        String docKey,
        String title,
        String sourceType,
        String sourceUri,
        String checksum,
        String version,
        String embeddingModel,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        UUID supersedesDocId,
        String authorityLevel,
        String scope,
        String topicKey,
        String metadataJson,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
