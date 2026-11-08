package com.lqx.ragmcp.eval;

import java.util.List;

/** Result of one (dataset, chunk strategy) run. */
public record EvalRunResult(EvalSummary summary, List<EvalQuestionResult> questions) {
}
