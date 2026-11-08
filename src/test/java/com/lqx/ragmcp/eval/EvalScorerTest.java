package com.lqx.ragmcp.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** T-UNIT-METRIC-01/02 plus no-answer and error accounting (docs/11 4.3). */
class EvalScorerTest {

    @Test
    void hitAtKAndMrrUseFirstMatchingRank() {
        EvalScorer scorer = new EvalScorer(3, 0);
        scorer.recordAnswerableRank(1, 100);
        scorer.recordAnswerableRank(3, 100);
        scorer.recordAnswerableRank(5, 100);

        EvalSummary summary = scorer.summary();

        assertThat(summary.answered()).isEqualTo(3);
        assertThat(summary.hit1()).isEqualTo(1);
        assertThat(summary.hit3()).isEqualTo(2);
        assertThat(summary.hit5()).isEqualTo(3);
        assertThat(summary.hitAt1()).isEqualTo(0.3333);
        assertThat(summary.hitAt3()).isEqualTo(0.6667);
        assertThat(summary.hitAt5()).isEqualTo(1.0);
        assertThat(summary.mrr()).isCloseTo((1.0 + 1.0 / 3.0 + 1.0 / 5.0) / 3.0,
                within(0.0001));
    }

    @Test
    void missContributesZeroAndRankAboveFiveIsNotHitAtFive() {
        EvalScorer scorer = new EvalScorer(2, 0);
        scorer.recordAnswerableRank(0, 100);
        scorer.recordAnswerableRank(6, 100);

        EvalSummary summary = scorer.summary();

        assertThat(summary.hitAt1()).isZero();
        assertThat(summary.hitAt3()).isZero();
        assertThat(summary.hitAt5()).isZero();
        // MRR is the reciprocal rank of the first hit in the returned list; with topK=5 a rank 6
        // item is never returned, this synthetic case only documents the arithmetic.
        assertThat(summary.mrr()).isEqualTo(0.0833);
    }

    @Test
    void errorsAreCountedSeparatelyAndExcludedFromMetricDenominator() {
        EvalScorer scorer = new EvalScorer(3, 0);
        scorer.recordAnswerableRank(1, 100);
        scorer.recordError(true);
        scorer.recordError(true);

        EvalSummary summary = scorer.summary();

        assertThat(summary.answered()).isEqualTo(1);
        assertThat(summary.errors()).isEqualTo(2);
        assertThat(summary.answerableErrors()).isEqualTo(2);
        assertThat(summary.hitAt5()).isEqualTo(1.0);
        assertThat(summary.mrr()).isEqualTo(1.0);
        assertThat(summary.qaCount()).isEqualTo(3);
    }

    @Test
    void unanswerableMetricsAreSeparateFromHitAtK() {
        EvalScorer scorer = new EvalScorer(1, 2);
        scorer.recordAnswerableRank(0, 100);
        scorer.recordUnanswerable(0, 100);
        scorer.recordUnanswerable(3, 100);

        EvalSummary summary = scorer.summary();

        assertThat(summary.hitAt5()).isZero();
        assertThat(summary.falsePositiveRate()).isEqualTo(0.5);
        assertThat(summary.noAnswerAccuracy()).isEqualTo(0.5);
    }

    @Test
    void unanswerableErrorsDoNotBecomeFalsePositives() {
        EvalScorer scorer = new EvalScorer(0, 2);
        scorer.recordUnanswerable(0, 100);
        scorer.recordError(false);

        EvalSummary summary = scorer.summary();

        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.unanswerableErrors()).isEqualTo(1);
        assertThat(summary.falsePositiveRate()).isZero();
        assertThat(summary.noAnswerAccuracy()).isEqualTo(1.0);
    }

    @Test
    void percentilesUseNearestRank() {
        List<Double> values = List.of(10.0, 20.0, 30.0, 40.0, 50.0,
                60.0, 70.0, 80.0, 90.0, 100.0);

        assertThat(EvalScorer.percentile(values, 0.50)).isEqualTo(50.0);
        assertThat(EvalScorer.percentile(values, 0.95)).isEqualTo(100.0);
        assertThat(EvalScorer.percentile(List.of(), 0.95)).isZero();
    }
}
