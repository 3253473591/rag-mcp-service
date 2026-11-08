package com.lqx.ragmcp.eval;

/**
 * Aggregate metrics for one (dataset, chunk strategy) run.
 *
 * <p>hit@k / MRR are computed over <b>answered</b> answerable questions: retrieval errors are
 * counted in {@code errors} and are neither a miss nor silently dropped
 * (docs/11-retrieval-and-eval-spec.md 4.3). The unanswerable metrics are computed over answered
 * unanswerable questions.</p>
 */
public record EvalSummary(
        int qaCount,
        int answerableCount,
        int unanswerableCount,
        int answered,
        int errors,
        int answerableErrors,
        int unanswerableErrors,
        long hit1,
        long hit3,
        long hit5,
        double hitAt1,
        double hitAt3,
        double hitAt5,
        double mrr,
        double falsePositiveRate,
        double noAnswerAccuracy,
        double p50Ms,
        double p95Ms) {
}
