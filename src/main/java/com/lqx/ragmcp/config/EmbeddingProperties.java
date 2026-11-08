package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    /** Ollama embedding model; must match kb_document.embedding_model. */
    private String model = "bge-m3";

    /** bge-m3 produces 1024-dim vectors; kb_chunk.embedding is VECTOR(1024). */
    private int dimension = 1024;

    /** Batch size; production value is 32 (contract: 16~32). */
    private int batchSize = 32;

    /** Extra attempts after the first call fails (contract: retry 2 times). */
    private int retries = 2;

    /** First retry backoff; doubles on each retry (500ms, 1000ms). */
    private long retryBackoffMs = 500;

    /** Per-batch timeout in seconds (contract: 120s). */
    private int timeoutSeconds = 120;

    /**
     * Concurrent embedding calls allowed from search/ingest. Search is stateless and docs/05
     * targets 10 concurrent retrievals; a pool of 2 serialized query embeddings and failed the
     * 10-concurrency p95 target even though pgvector/HTTP were fast.
     */
    private int maxConcurrentCalls = 10;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
    }
}
