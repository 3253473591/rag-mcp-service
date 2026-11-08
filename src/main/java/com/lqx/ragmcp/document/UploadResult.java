package com.lqx.ragmcp.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lqx.ragmcp.chunk.ChunkStrategy;

/** Result of one upload: persisted document plus chunk counts and idempotency flag. */
public record UploadResult(DocumentRecord document, Map<ChunkStrategy, Integer> chunkCounts,
                           boolean duplicate, long tookMs) {

    public int totalChunks() {
        return chunkCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public List<String> strategies() {
        return chunkCounts.keySet().stream().map(ChunkStrategy::dbValue).toList();
    }

    public UUID docId() {
        return document.id();
    }
}
