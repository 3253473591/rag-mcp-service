package com.lqx.ragmcp.search;

import java.util.List;

/**
 * Deterministic output of {@link AbstainGuard}. The raw score metrics are kept even when
 * {@code reason == NONE} so the response can always carry auditable diagnostics.
 */
public record AbstainDecision(
        AbstainReason reason,
        double topScore,
        double secondScore,
        double scoreGap,
        int candidateCount,
        int denseCount,
        List<Double> candidateScores) {

    public AbstainDecision {
        candidateScores = candidateScores == null ? List.of() : List.copyOf(candidateScores);
    }

    public boolean abstained() {
        return reason != null && reason.abstained();
    }

    /** Re-classify an empty-result response without inventing new scores. */
    public AbstainDecision withReason(AbstainReason newReason) {
        return new AbstainDecision(newReason, topScore, secondScore, scoreGap,
                candidateCount, denseCount, candidateScores);
    }
}
