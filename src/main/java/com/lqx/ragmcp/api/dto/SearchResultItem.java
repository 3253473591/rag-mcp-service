package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;

/**
 * One retrieval result.
 *
 * <p>{@code score} is the raw RRF score, not a 0..1 probability. When the rerank stage is enabled
 * ({@code app.search.rerank.enabled=true}), results are ordered by {@code rerankScore} instead;
 * that field is {@code null} when reranking is disabled, so old clients can keep sorting by
 * {@code score} exactly as before. See docs/02-api-contract.md 2.9.</p>
 */
public record SearchResultItem(
        String chunkId,
        String docId,
        String docKey,
        String docVersion,
        String docStatus,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String title,
        String clauseId,
        String clausePath,
        Integer pageNo,
        Integer pageEnd,
        double score,
        Double rerankScore,
        String content,
        String citation) {
}
