package com.lqx.ragmcp.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One line of {@code eval/qa.jsonl}. Per-question {@code asOf} / {@code includeSuperseded}
 * override the run-wide defaults (used by historical questions against a superseded version).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalQaItem(
        String id,
        String question,
        boolean answerable,
        EvalGold gold,
        String asOf,
        Boolean includeSuperseded) {
}
