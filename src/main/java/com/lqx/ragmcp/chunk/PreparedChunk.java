package com.lqx.ragmcp.chunk;

/**
 * Chunk ready for persistence: strategy, index inside the strategy, content and the
 * metadata columns of kb_chunk.
 */
public record PreparedChunk(ChunkStrategy strategy,
                            int chunkIndex,
                            String content,
                            int tokenCount,
                            String clauseId,
                            String clausePath,
                            Integer pageNo,
                            boolean table) {
}
