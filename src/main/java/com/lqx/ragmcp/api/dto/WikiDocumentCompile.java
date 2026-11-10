package com.lqx.ragmcp.api.dto;

/**
 * One document touched by the wiki compile run. {@code sectionCount} is the number of leaf
 * sections attempted; {@code successCount + failedCount + skippedCount == sectionCount} unless a
 * document-level failure (e.g. embedding outage) prevented the run, which is reported in
 * {@code failures} and counted in the top-level totals.
 */
public record WikiDocumentCompile(
        String docId,
        String docKey,
        String version,
        String title,
        int sectionCount,
        int successCount,
        int failedCount,
        int skippedCount,
        int pageCount,
        int llmCalls,
        long promptTokens,
        long completionTokens,
        long tookMs) {
}
