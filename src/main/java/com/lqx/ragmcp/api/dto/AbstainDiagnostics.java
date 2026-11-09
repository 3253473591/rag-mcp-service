package com.lqx.ragmcp.api.dto;

import java.util.List;

/**
 * Auditable details for the score-based abstention guard. Present on every search response
 * (reason {@code NONE} for normal results) so an operator can reproduce why a request was
 * rejected. {@code candidateScores} is the descending cosine list the decision was made from.
 */
public record AbstainDiagnostics(
        double topScore,
        double secondScore,
        double scoreGap,
        int candidateCount,
        int denseCount,
        double minTopScore,
        double highConfidenceTopScore,
        int minDenseCandidates,
        double minScoreGap,
        boolean enabled,
        String reason,
        List<Double> candidateScores) {

    public AbstainDiagnostics {
        candidateScores = candidateScores == null ? List.of() : List.copyOf(candidateScores);
    }

    /** Diagnostics for a response that never reached retrieval (e.g. an empty mandate scope). */
    public static AbstainDiagnostics emptyNoMatch() {
        return new AbstainDiagnostics(0.0d, 0.0d, 0.0d, 0, 0,
                0.0d, 0.0d, 0, 0.0d, true, "NO_MATCH", List.of());
    }

    /** Keep the diagnostics reason aligned with the final response-level reason. */
    public AbstainDiagnostics withReason(String newReason) {
        return new AbstainDiagnostics(topScore, secondScore, scoreGap, candidateCount, denseCount,
                minTopScore, highConfidenceTopScore, minDenseCandidates, minScoreGap,
                enabled, newReason, candidateScores);
    }
}
