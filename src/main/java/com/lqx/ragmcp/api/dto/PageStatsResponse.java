package com.lqx.ragmcp.api.dto;

import java.util.List;

/** GET /api/v1/admin/pages/stats response (read-only page layer observability). */
public record PageStatsResponse(
        int documentCount,
        int pageCount,
        long totalTokens,
        double avgTokens,
        List<PageDocumentStats> documents) {
}
