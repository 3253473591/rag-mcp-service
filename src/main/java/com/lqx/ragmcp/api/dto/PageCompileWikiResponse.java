package com.lqx.ragmcp.api.dto;

import java.util.List;

/**
 * POST /api/v1/admin/pages/compile-wiki response.
 *
 * <p>{@code sectionCount = successCount + failedCount + skippedCount}. {@code skippedCount} are
 * sections whose model output was still not valid JSON after the parse retry (no page written);
 * {@code failedCount} are transport/API/embedding failures. {@code promptTokens} and
 * {@code completionTokens} sum every call, including the parse retry, so they are the billable
 * usage of the run.</p>
 */
public record PageCompileWikiResponse(
        String model,
        int documentCount,
        int sectionCount,
        int successCount,
        int failedCount,
        int skippedCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long tookMs,
        List<WikiDocumentCompile> documents,
        List<WikiCompileFailure> failures) {
}
