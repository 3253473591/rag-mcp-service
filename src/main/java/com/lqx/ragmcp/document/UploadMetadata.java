package com.lqx.ragmcp.document;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lqx.ragmcp.chunk.ChunkStrategy;

/** Validated upload metadata (multipart fields). */
public record UploadMetadata(
        String title,
        String docKey,
        String version,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String authorityLevel,
        String scope,
        UUID supersedesDocId,
        List<ChunkStrategy> chunkStrategies) {
}
