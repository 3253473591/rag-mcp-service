package com.lqx.ragmcp.search;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A chunk returned by one retrieval route. {@code routeScore} is the cosine similarity or
 * trigram similarity for diagnostics/tests only; the public API exposes the fused RRF score.
 */
public record SearchCandidate(
        UUID chunkId,
        UUID docId,
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
        String content,
        String chunkStrategy,
        int chunkIndex,
        double routeScore) {
}
