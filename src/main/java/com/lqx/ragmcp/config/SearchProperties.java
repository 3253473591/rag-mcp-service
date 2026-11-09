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
