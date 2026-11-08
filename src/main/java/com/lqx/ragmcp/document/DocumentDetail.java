package com.lqx.ragmcp.document;

import java.util.Map;

import com.lqx.ragmcp.chunk.ChunkStrategy;

/** Document detail: metadata + total/per-strategy chunk counts. */
public record DocumentDetail(DocumentRecord document, Map<ChunkStrategy, Integer> chunkCounts) {

    public int totalChunks() {
        return chunkCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
