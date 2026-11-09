package com.lqx.ragmcp.search;

import com.lqx.ragmcp.config.AbstainProperties;
import com.lqx.ragmcp.config.SearchProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, server-side abstention rule. Input is the vector route's candidates (their
 * {@link SearchCandidate#routeScore()} is the cosine similarity) and output is
 * {@link AbstainReason}. No model, prompt or caller-controlled request field participates.
 *
 * <p>Rule (see {@link AbstainProperties}):</p>
 * <pre>
 * NO_MATCH   : no cosine at all, or top1 &lt; minTopScore
 * TOO_NOISY  : top1 &gt;= minTopScore AND top1 &lt; highConfidenceTopScore
 *              AND denseCount &gt;= minDenseCandidates AND (top1 - top2) &lt; minScoreGap
 * NONE       : otherwise
 * </pre>
 *
 * <p>The two reasons are deliberately different branches: an absolute floor answers "nothing is
 * relevant", the gray-zone test answers "many things are similarly relevant, so ranking cannot
 * decide". A single "below threshold -&gt; NO_MATCH" branch would not distinguish them.</p>
 */
@Component
public class AbstainGuard {

    private final SearchProperties properties;

    public AbstainGuard(SearchProperties properties) {
        this.properties = properties;
    }

    /**
     * @param vectorCandidates candidates returned by the vector route; their {@code routeScore} is
     *                         the cosine similarity. May be empty.
     * @param vectorRouteEnabled false for {@code strategy=keyword}, where no cosine exists; the
     *                           guard then stays neutral and empty-result fallback is handled by
     *                           the caller.
     */
    public AbstainDecision decide(List<SearchCandidate> vectorCandidates, boolean vectorRouteEnabled) {
        if (!vectorRouteEnabled) {
            return new AbstainDecision(AbstainReason.NONE, 0.0d, 0.0d, 0.0d, 0, 0, List.of());
        }

        List<Double> scores = vectorCandidates == null
                ? List.of()
                : vectorCandidates.stream()
                        .filter(Objects::nonNull)
                        .map(SearchCandidate::routeScore)
                        .sorted(Comparator.reverseOrder())
                        .toList();

        AbstainProperties config = properties.getAbstain();
        int candidateCount = scores.size();
        double topScore = candidateCount == 0 ? 0.0d : scores.get(0);
        double secondScore = candidateCount < 2 ? 0.0d : scores.get(1);
        double scoreGap = topScore - secondScore;
        int denseCount = (int) scores.stream()
                .filter(score -> score >= config.getMinTopScore())
                .count();

        AbstainReason reason;
        if (candidateCount == 0) {
            reason = AbstainReason.NO_MATCH;
        } else if (!config.isEnabled()) {
            reason = AbstainReason.NONE;
        } else if (topScore < config.getMinTopScore()) {
            reason = AbstainReason.NO_MATCH;
        } else if (topScore < config.getHighConfidenceTopScore()
                && denseCount >= config.getMinDenseCandidates()
                && scoreGap < config.getMinScoreGap()) {
            reason = AbstainReason.TOO_NOISY;
        } else {
            reason = AbstainReason.NONE;
        }

        return new AbstainDecision(reason, topScore, secondScore, scoreGap,
                candidateCount, denseCount, scores);
    }
}
