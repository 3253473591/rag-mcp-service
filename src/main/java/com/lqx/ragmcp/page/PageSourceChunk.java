package com.lqx.ragmcp.page;

import java.util.UUID;

/**
 * One {@code chunk_strategy='heading'} chunk as read back from {@code kb_chunk} for aggregation.
 * Only the columns the page layer needs are carried; the embedding is not copied.
 */
public record PageSourceChunk(
        UUID chunkId,
        int chunkIndex,
        String content,
        int tokenCount,
        String clauseId,
        String clausePath,
        Integer pageNo,
        Integer pageEnd) {
}
