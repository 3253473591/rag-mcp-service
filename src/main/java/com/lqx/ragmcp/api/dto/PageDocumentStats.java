package com.lqx.ragmcp.api.dto;

/**
 * Per-document page statistics for the Phase 10 acceptance report.
 * {@code pageCount} counts page rows (parts included); {@code pageGroupCount} counts distinct
 * page keys before oversize splitting.
 */
public record PageDocumentStats(
        String docId,
        String docKey,
        String title,
        String version,
        String status,
        int pageCount,
        int pageGroupCount,
        long totalTokens,
        double avgTokens,
        int maxTokens) {
}
