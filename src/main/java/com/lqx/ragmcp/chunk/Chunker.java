package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.parse.Block;

import java.util.List;

public interface Chunker {

    ChunkStrategy strategy();

    /**
     * Splits parsed blocks into chunk drafts. Implementations drop blank chunks.
     */
    List<ChunkDraft> chunk(List<Block> blocks);
}
