package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retrieval tuning knobs (docs/05-non-functional.md 2.1/3). */
@ConfigurationProperties(prefix = "app.search")
public class SearchProperties {

    /** Two routes (vector + keyword) each return at most this many candidates before RRF. */
    private int candidateLimit = 30;

    /** Keyword branch truncates overly long queries to this many characters. */
    private int keywordMaxChars = 200;

    /**
     * Keyword branch splits a natural-language question into at most this many lexical terms
     * (docs/11-retrieval-and-eval-spec.md 3.2). Each term is one {@code ILIKE} branch, so this
     * caps the SQL size; 0 disables the keyword route.
     */
    private int keywordMaxTerms = 8;

    /**
     * RRF weight of the keyword route relative to the vector route (Phase 8). Keyword terms match
     * an OR of short patterns, so a single generic term can hit many chunks; at weight 1.0 those
     * hits tie with a vector rank-1 and the tie-break becomes arbitrary. Calibrated by
     * {@code scripts/eval.ps1} — see docs/acceptance/phase8-keyword.md.
     */
    private double keywordWeight = 1.0d;

    /**
     * Distinct-term threshold for the hybrid route. 1 means "any extracted term"; 2 requires a chunk
     * to contain at least two query concepts before it can enter RRF. The explicit
     * {@code strategy=keyword} route always uses 1.
     */
    private int keywordMinTermHits = 2;

    /**
     * pgvector &gt;= 0.8 iterative scan. With metadata filters HNSW can under-return; relaxed_order
     * keeps scanning until enough filtered rows are collected. Set false only for an older pgvector.
     */
    private boolean hnswIterativeScan = true;

    /** Phase 7 score-based abstention guard ({@code app.search.abstain.*}). */
    private AbstainProperties abstain = new AbstainProperties();

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public int getKeywordMaxChars() {
        return keywordMaxChars;
    }

    public void setKeywordMaxChars(int keywordMaxChars) {
        this.keywordMaxChars = keywordMaxChars;
    }

    public int getKeywordMaxTerms() {
        return keywordMaxTerms;
    }

    public void setKeywordMaxTerms(int keywordMaxTerms) {
        this.keywordMaxTerms = keywordMaxTerms;
    }

    public double getKeywordWeight() {
        return keywordWeight;
    }

    public void setKeywordWeight(double keywordWeight) {
        this.keywordWeight = keywordWeight;
    }

    public int getKeywordMinTermHits() {
        return keywordMinTermHits;
    }

    public void setKeywordMinTermHits(int keywordMinTermHits) {
        this.keywordMinTermHits = keywordMinTermHits;
    }

    public boolean isHnswIterativeScan() {
        return hnswIterativeScan;
    }

    public void setHnswIterativeScan(boolean hnswIterativeScan) {
        this.hnswIterativeScan = hnswIterativeScan;
    }

    public AbstainProperties getAbstain() {
        return abstain;
    }

    public void setAbstain(AbstainProperties abstain) {
        this.abstain = abstain;
    }
}
