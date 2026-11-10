package com.lqx.ragmcp.config;

/**
 * Rerank-stage configuration ({@code app.search.rerank.*}), Phase 9.
 *
 * <p><b>Why.</b> RRF is a rank fusion: it keeps the order of each route but throws away the
 * <em>magnitude</em> of the underlying similarity. A keyword rescue chunk that matches two query
 * terms therefore enters the fused list with the same {@code 1/61} contribution as the true vector
 * rank-1, and the final order falls back to the docId tie-break. The rerank stage re-scores the
 * fused head with absolute signals that are already available, so the lexical route stays a
 * recall/rescue signal instead of an equal-ranking vote.</p>
 *
 * <p><b>Score.</b> {@code rerankScore = rrfScore + Σ weight_i × feature_i}, where every feature is
 * normalized to {@code [0, 1]} and every weight is non-negative:</p>
 * <ul>
 *   <li>{@code vectorScore}: the candidate's cosine similarity, clamped to {@code [0, 1]}. A
 *       candidate that the vector route did not return has no cosine evidence and contributes
 *       {@code 0}; this is deliberate, it must not be confused with the keyword route's trigram
 *       similarity ({@code SearchCandidate.routeScore()} is route-specific).</li>
 *   <li>{@code termCoverage}: fraction of the query's {@code KeywordTerms} found in the content
 *       (case-insensitive containment, recomputed in process for the ≤30 candidates).</li>
 *   <li>{@code titleMatch}: {@link com.lqx.ragmcp.search.SectionTitleMatcher#boost} divided by its
 *       maximum, so a leaf section title named by the query counts once more, at a calibrated
 *       weight, instead of being an ordering-only hack inside the fusion.</li>
 *   <li>{@code clause}: {@code 1} when {@code clauseId} is present, else {@code 0}. Structured
 *       clause chunks are answer units; chunks without one are often extraction fragments.</li>
 *   <li>{@code length}: {@code min(1, contentChars / minContentChars)}. Chunks shorter than
 *       {@code minContentChars} are usually fragments ("90 mmHg"); longer chunks are not rewarded
 *       further.</li>
 * </ul>
 *
 * <p>Values are calibrated on {@code eval/qa.jsonl} with bge-m3; see
 * {@code docs/acceptance/phase9-rerank.md} for the sweep, the ablation and the p95 impact. Weights
 * are configuration, not Java constants, so operations can retune without a rebuild.</p>
 */
public class RerankProperties {

    /**
     * Master switch. {@code false} restores the Phase 8 pipeline exactly (heading boost inside
     * RRF, results sorted by raw RRF, {@code rerankScore} absent from the response).
     */
    private boolean enabled = true;

    /**
     * How many of the top fused candidates are re-ordered. The effective window is
     * {@code max(topK, topN)} so every returned result is reranked; the default 20 covers the
     * maximum allowed {@code topK}.
     */
    private int topN = 20;

    /** Weight of the absolute cosine similarity (clamped to [0,1]). */
    private double weightVectorScore = 0.25d;

    /** Weight of the fraction of query terms contained in the chunk content. */
    private double weightTermCoverage = 0.01d;

    /** Weight of the normalized section-title match. */
    private double weightTitleMatch = 0.01d;

    /** Weight of the "clauseId present" structural signal. */
    private double weightClause = 0.02d;

    /** Weight of the short-chunk length ramp; {@code 0} disables the signal. */
    private double weightLength = 0.004d;

    /** Character count at which the length ramp saturates. */
    private int minContentChars = 80;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public double getWeightVectorScore() {
        return weightVectorScore;
    }

    public void setWeightVectorScore(double weightVectorScore) {
        this.weightVectorScore = weightVectorScore;
    }

    public double getWeightTermCoverage() {
        return weightTermCoverage;
    }

    public void setWeightTermCoverage(double weightTermCoverage) {
        this.weightTermCoverage = weightTermCoverage;
    }

    public double getWeightTitleMatch() {
        return weightTitleMatch;
    }

    public void setWeightTitleMatch(double weightTitleMatch) {
        this.weightTitleMatch = weightTitleMatch;
    }

    public double getWeightClause() {
        return weightClause;
    }

    public void setWeightClause(double weightClause) {
        this.weightClause = weightClause;
    }

    public double getWeightLength() {
        return weightLength;
    }

    public void setWeightLength(double weightLength) {
        this.weightLength = weightLength;
    }

    public int getMinContentChars() {
        return minContentChars;
    }

    public void setMinContentChars(int minContentChars) {
        this.minContentChars = minContentChars;
    }
}
