package com.lqx.ragmcp.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Gold answer locator for one answerable QA item (docs/11-retrieval-and-eval-spec.md 4.1).
 *
 * <p>{@code clauseId} is the strongest locator; when the retrieved chunk has no clause id
 * (fixed-window chunks and some heading chunks), matching falls back to {@code pageRange}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalGold(
        String docKey,
        String version,
        String clauseId,
        List<Integer> pageRange) {
}
