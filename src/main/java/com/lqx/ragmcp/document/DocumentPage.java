package com.lqx.ragmcp.document;

import java.util.List;
import java.util.Map;

import com.lqx.ragmcp.chunk.ChunkStrategy;

/** Document list page. */
public record DocumentPage(List<DocumentRow> items, long total, int limit, int offset) {

    public record DocumentRow(DocumentRecord document, int chunkCount) {
    }
}
