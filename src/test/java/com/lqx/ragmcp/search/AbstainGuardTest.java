package com.lqx.ragmcp.search;

import com.lqx.ragmcp.config.SearchProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for the deterministic two-branch abstention rule. */
class AbstainGuardTest {

    private final SearchProperties properties = new SearchProperties();
    private final AbstainGuard guard = new AbstainGuard(properties);

    @Test
    void noCandidatesIsNoMatch() {
        AbstainDecision decision = guard.decide(List.of(), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NO_MATCH);
        assertThat(decision.abstained()).isTrue();
        assertThat(decision.topScore()).isZero();
        assertThat(decision.candidateCount()).isZero();
        assertThat(decision.candidateScores()).isEmpty();
    }

    @Test
    void topScoreBelowAbsoluteFloorIsNoMatch() {
        AbstainDecision decision = guard.decide(scores(0.49, 0.48, 0.47), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NO_MATCH);
        assertThat(decision.topScore()).isEqualTo(0.49);
        assertThat(decision.candidateCount()).isEqualTo(3);
    }

    @Test
    void grayZoneWithManyDenseCandidatesAndTinyGapIsTooNoisy() {
        AbstainDecision decision = guard.decide(scores(0.60, 0.598, 0.596, 0.594, 0.592,
                0.590, 0.588, 0.586, 0.584, 0.582, 0.580, 0.578), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.TOO_NOISY);
        assertThat(decision.denseCount()).isEqualTo(12);
        assertThat(decision.scoreGap()).isCloseTo(0.002, within());
    }

    @Test
    void grayZoneNeedsEnoughDenseCandidates() {
        AbstainDecision decision = guard.decide(scores(0.60, 0.598, 0.596), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NONE);
        assertThat(decision.denseCount()).isEqualTo(3);
    }

    @Test
    void grayZoneNeedsTinyTopGap() {
        AbstainDecision decision = guard.decide(scores(0.60, 0.55, 0.55, 0.55, 0.55,
                0.55, 0.55, 0.55, 0.55, 0.55, 0.55, 0.55), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NONE);
        assertThat(decision.scoreGap()).isCloseTo(0.05, within());
    }

    @Test
    void highConfidenceTopScoreIsNotNoisyEvenWhenDenseAndFlat() {
        AbstainDecision decision = guard.decide(scores(0.70, 0.699, 0.698, 0.697, 0.696,
                0.695, 0.694, 0.693, 0.692, 0.691, 0.690, 0.689), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NONE);
        assertThat(decision.denseCount()).isEqualTo(12);
    }

    @Test
    void disabledGuardStillReportsMetricsButNeverAbstains() {
        properties.getAbstain().setEnabled(false);

        AbstainDecision decision = guard.decide(scores(0.10, 0.09), true);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NONE);
        assertThat(decision.topScore()).isEqualTo(0.10);
        assertThat(decision.candidateCount()).isEqualTo(2);
        assertThat(decision.denseCount()).isZero();
    }

    @Test
    void keywordRouteHasNoCosineEvidenceAndStaysNeutral() {
        AbstainDecision decision = guard.decide(scores(0.99), false);

        assertThat(decision.reason()).isEqualTo(AbstainReason.NONE);
        assertThat(decision.candidateCount()).isZero();
        assertThat(decision.candidateScores()).isEmpty();
    }

    @Test
    void candidateScoresAreSortedDescendingForAudit() {
        AbstainDecision decision = guard.decide(scores(0.70, 0.80, 0.60), true);

        assertThat(decision.candidateScores()).containsExactly(0.80, 0.70, 0.60);
        assertThat(decision.topScore()).isEqualTo(0.80);
        assertThat(decision.secondScore()).isEqualTo(0.70);
    }

    private static List<SearchCandidate> scores(double... values) {
        List<SearchCandidate> candidates = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            candidates.add(new SearchCandidate(
                    new UUID(0, i + 1),
                    new UUID(0, 1000 + i),
                    "doc", "2025", "ACTIVE", LocalDate.of(2025, 1, 1), null,
                    "title", null, null, 1, 1, "content", "heading", i, values[i]));
        }
        return candidates;
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
