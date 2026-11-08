package com.lqx.ragmcp.eval;

import com.lqx.ragmcp.api.dto.SearchResultItem;

import java.util.List;

/** Per-question outcome. {@code results} keeps the returned topK for citation spot-checks. */
public record EvalQuestionResult(
        String id,
        boolean answerable,
        int rank,
        int resultCount,
        boolean error,
        String errorMessage,
        double latencyMs,
        String asOf,
        boolean includeSuperseded,
        List<SearchResultItem> results) {
}
