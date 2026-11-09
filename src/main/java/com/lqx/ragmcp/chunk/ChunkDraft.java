package com.lqx.ragmcp.chunk;

/**
 * One chunk produced by a chunker before token counting / persistence.
 */
public record ChunkDraft(String content,
                         String clauseId,
                         String clausePath,
                         Integer pageNo,
                         Integer pageEnd,
                         boolean table) {
}
