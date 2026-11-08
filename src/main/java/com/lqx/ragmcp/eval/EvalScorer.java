package com.lqx.ragmcp.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates hit@k, MRR, no-answer metrics, error counts and latencies.
 *
 * <p>Error policy (docs/11-retrieval-and-eval-spec.md 4.3): an errored question is counted in
 * {@code errors} and removed from the metric denominator. It is never counted as a miss and never
 * silently discarded.</p>
 */
public final class EvalScorer {

    private final int answerableCount;
    private final int unanswerableCount;

    private int answered;
    private int errors;
    private int answerableErrors;
    private int unanswerableErrors;

    private long hit1;
    private long hit3;
    private long hit5;
    private double reciprocalRankSum;

    private int unanswerableAnswered;
    private int falsePositive;

    private final List<Double> latencies = new ArrayList<>();

    public EvalScorer(int answerableCount, int unanswerableCount) {
        if (answerableCount < 0 || unanswerableCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        this.answerableCount = answerableCount;
        this.unanswerableCount = unanswerableCount;
    }

    /** Record a successful answerable retrieval. {@code rank == 0} means a miss. */
    public void recordAnswerableRank(int rank, double latencyMs) {
        answered++;
        if (rank >= 1) {
            if (rank <= 5) {
                hit5++;
            }
            if (rank <= 3) {
                hit3++;
            }
            if (rank == 1) {
                hit1++;
            }
            reciprocalRankSum += 1.0d / rank;
        }
        latencies.add(latencyMs);
    }

    /** Record a successful unanswerable retrieval. */
    public void recordUnanswerable(int resultCount, double latencyMs) {
        unanswerableAnswered++;
        if (resultCount > 0) {
            falsePositive++;
        }
        latencies.add(latencyMs);
    }

    /** Record a failed call; it is excluded from hit@k/MRR denominators. */
    public void recordError(boolean answerable) {
        errors++;
        if (answerable) {
            answerableErrors++;
        } else {
            unanswerableErrors++;
        }
    }

    public EvalSummary summary() {
        double hitAt1 = rate(hit1, answered);
        double hitAt3 = rate(hit3, answered);
        double hitAt5 = rate(hit5, answered);
        double mrr = answered == 0 ? 0.0d : round4(reciprocalRankSum / answered);
        double falsePositiveRate = rate(falsePositive, unanswerableAnswered);
        double noAnswerAccuracy = unanswerableAnswered == 0
                ? 0.0d
                : round4((unanswerableAnswered - falsePositive) / (double) unanswerableAnswered);
        double p50 = percentile(latencies, 0.50);
        double p95 = percentile(latencies, 0.95);
        return new EvalSummary(
                answerableCount + unanswerableCount,
                answerableCount,
                unanswerableCount,
                answered,
                errors,
                answerableErrors,
                unanswerableErrors,
                hit1,
                hit3,
                hit5,
                hitAt1,
                hitAt3,
                hitAt5,
                mrr,
                falsePositiveRate,
                noAnswerAccuracy,
                p50,
                p95);
    }

    public List<Double> latencies() {
        return List.copyOf(latencies);
    }

    /**
     * Nearest-rank percentile, matching the PowerShell harness: rank = ceil(p * n) - 1.
     */
    public static double percentile(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        if (percentile <= 0.0d) {
            percentile = 0.0d;
        } else if (percentile >= 1.0d) {
            percentile = 1.0d;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile * sorted.size()) - 1;
        if (rank < 0) {
            rank = 0;
        }
        if (rank >= sorted.size()) {
            rank = sorted.size() - 1;
        }
        return round2(sorted.get(rank));
    }

    private static double rate(long numerator, int denominator) {
        return denominator == 0 ? 0.0d : round4(numerator / (double) denominator);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
