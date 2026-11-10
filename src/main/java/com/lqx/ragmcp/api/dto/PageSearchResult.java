package com.lqx.ragmcp.api.dto;

import java.util.List;

/**
 * One page hit from POST /api/v1/pages/search.
 *
 * <p>{@code leafClauseId} is the clause id of the page's own leaf heading; {@code clauseIds} is
 * every distinct clause the page covers. The strict page-side A/B metric compares
 * {@code leafClauseId} (not {@code clauseIds}) against gold, precisely so a page cannot score a
 * hit just because the right clause appears somewhere inside it.</p>
 */
public record PageSearchResult(
        String pageId,
        String pageKey,
        String title,
        String clausePath,
        String leafClauseId,
        List<String> clauseIds,
        Integer pageIndex,
        Integer part,
        Integer tokenCount,
        PageRange pageRange,
        double score,
        String content,
        String citation,
        PageDocumentInfo document) {
}
