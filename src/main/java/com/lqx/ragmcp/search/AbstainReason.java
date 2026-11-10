package com.lqx.ragmcp.search;

/**
 * Server-side retrieval guard outcome. {@code NONE} means the evidence is considered usable;
 * the other two values are the only reasons for abstaining.
 *
 * <p>The decision is a deterministic function of the candidate cosine scores and the configured
 * thresholds. It is never delegated to a model or prompt: the MCP tool description must stay
 * neutral so a caller cannot talk the service into or out of abstaining.</p>
 */
public enum AbstainReason {

    /** Evidence is sufficient; return the fused results unchanged. */
    NONE,

    /** Top-1 cosine is below the absolute floor, or no candidate carried a cosine at all. */
    NO_MATCH,

    /**
     * Top-1 is above the absolute floor but the score profile is not discriminative: the top-1 to
     * top-2 gap is tiny and too many candidates sit above the floor (a "gray zone" query).
     */
    TOO_NOISY;

    public boolean abstained() {
        return this != NONE;
    }
}
