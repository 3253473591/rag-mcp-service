package com.lqx.ragmcp.page;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One {@code kb_page} row plus its document metadata, as returned by the page vector route.
 * {@code score} is the cosine similarity from pgvector ({@code 1 - distance}).
 */
public record PageCandidate(
        UUID pageId,
        UUID documentId,
        String docKey,
        String docVersion,
        String docStatus,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String docTitle,
        String pageKey,
        String clausePath,
        String leafClauseId,
        List<String> clauseIds,
        String title,
        int pageIndex,
        int part,
        String body,
        int tokenCount,
        Integer pageNo,
        Integer pageEnd,
        List<UUID> sourceChunkIds,
        double score) {
}
