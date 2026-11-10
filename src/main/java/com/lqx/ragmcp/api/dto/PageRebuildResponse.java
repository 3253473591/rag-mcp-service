package com.lqx.ragmcp.api.dto;

import java.util.List;

/**
 * POST /api/v1/admin/pages/rebuild response. {@code pageCount} counts rows (page parts included);
 * {@code documents[].pageGroupCount} counts leaf sections before oversize splitting.
 */
public record PageRebuildResponse(
        int documentCount,
        int pageCount,
        int chunkCount,
        long tookMs,
        List<PageDocumentRebuild> documents,
        List<PageRebuildFailure> failures) {
}
