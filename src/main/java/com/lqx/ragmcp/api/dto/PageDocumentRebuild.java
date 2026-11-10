package com.lqx.ragmcp.api.dto;

/** One document's page rebuild outcome (counts only; body text is not returned here). */
public record PageDocumentRebuild(
        String docId,
        String docKey,
        String version,
        String title,
        int chunkCount,
        int pageGroupCount,
        int pageCount,
        long totalTokens,
        double avgTokens) {
}
