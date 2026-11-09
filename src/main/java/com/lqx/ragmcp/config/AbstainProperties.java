package com.lqx.ragmcp.config;

/**
 * Score-based abstention guard configuration ({@code app.search.abstain.*}).
 *
 * <p>Defaults were calibrated on {@code eval/qa.jsonl} (35 answerable + 5 unanswerable, bge-m3,
 * heading/fixed) and are documented in {@code docs/acceptance/phase7-abstain.md}. They are
 * configuration, not Java constants, so operations can tighten or relax them without a rebuild.</p>
 *
 * <p>Decision rule (implemented by {@code AbstainGuard}):</p>
 * <ol>
 *   <li>No candidate score, or top-1 &lt; {@code minTopScore} -&gt; {@code NO_MATCH}.</li>
 *   <li>Top-1 &lt; {@code highConfidenceTopScore}, at least {@code minDenseCandidates} candidates
 *       are &gt;= {@code minTopScore}, and top-1 - top-2 &lt; {@code minScoreGap}
 *       -&gt; {@code TOO_NOISY}.</li>
 *   <li>Otherwise -&gt; {@code NONE}.</li>
 * </ol>
 */
public class AbstainProperties {

    /** Master switch. Disabling it never disables the empty-result message fallback. */
    private boolean enabled = true;

    /** Absolute floor for the best cosine; below it the KB has no plausible answer. */
    private double minTopScore = 0.55d;

    /**
     * Upper bound of the ambiguous gray zone. At or above this score the top-1 is treated as
     * confident even when many other candidates are close.
     */
    private double highConfidenceTopScore = 0.63d;

    /** Minimum number of candidates at/above {@code minTopScore} required for {@code TOO_NOISY}. */
    private int minDenseCandidates = 8;

    /** Top-1 to top-2 gap below which the ranking is considered non-discriminative. */
    private double minScoreGap = 0.02d;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getMinTopScore() {
        return minTopScore;
    }

    public void setMinTopScore(double minTopScore) {
        this.minTopScore = minTopScore;
    }

    public double getHighConfidenceTopScore() {
        return highConfidenceTopScore;
    }

    public void setHighConfidenceTopScore(double highConfidenceTopScore) {
        this.highConfidenceTopScore = highConfidenceTopScore;
    }

    public int getMinDenseCandidates() {
        return minDenseCandidates;
    }

    public void setMinDenseCandidates(int minDenseCandidates) {
        this.minDenseCandidates = minDenseCandidates;
    }

    public double getMinScoreGap() {
        return minScoreGap;
    }

    public void setMinScoreGap(double minScoreGap) {
        this.minScoreGap = minScoreGap;
    }
}
