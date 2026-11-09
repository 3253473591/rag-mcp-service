package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;

/** One retrieval result. {@code score} is the raw RRF score, not a 0..1 probability. */
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
        String content,
        String citation) {
}
