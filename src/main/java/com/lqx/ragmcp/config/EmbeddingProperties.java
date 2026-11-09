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

    /**
     * Phase 6b: maximum number of concurrent retrieval queries coalesced into one
     * {@code embed(List)} call. 1 disables batching. Document ingestion is unaffected.
     */
    private int queryBatchSize = 8;

    /** Phase 6b: how long to collect concurrent queries (milliseconds) before dispatching. */
    private long queryBatchWindowMs = 5;

    /** Phase 6b: bounded LRU cache capacity for query vectors; 0 disables the cache. */
    private int queryCacheSize = 1000;

    /** Phase 6b: query cache TTL in seconds; 0 disables the cache. */
    private long queryCacheTtlSeconds = 300;

    /** Phase 6b: bounded number of queries waiting to be batched; overflow returns 503. */
    private int queryQueueCapacity = 200;

    /** Phase 6b: number of batch calls that may run against Ollama at the same time. */
    private int queryMaxConcurrentBatches = 2;

    /** Phase 6b: upper bound on how long one query waits for its batch result. */
    private long queryWaitTimeoutMs = 130_000;

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

    public int getQueryBatchSize() {
        return queryBatchSize;
    }

    public void setQueryBatchSize(int queryBatchSize) {
        this.queryBatchSize = queryBatchSize;
    }

    public long getQueryBatchWindowMs() {
        return queryBatchWindowMs;
    }

    public void setQueryBatchWindowMs(long queryBatchWindowMs) {
        this.queryBatchWindowMs = queryBatchWindowMs;
    }

    public int getQueryCacheSize() {
        return queryCacheSize;
    }

    public void setQueryCacheSize(int queryCacheSize) {
        this.queryCacheSize = queryCacheSize;
    }

    public long getQueryCacheTtlSeconds() {
        return queryCacheTtlSeconds;
    }

    public void setQueryCacheTtlSeconds(long queryCacheTtlSeconds) {
        this.queryCacheTtlSeconds = queryCacheTtlSeconds;
    }

    public int getQueryQueueCapacity() {
        return queryQueueCapacity;
    }

    public void setQueryQueueCapacity(int queryQueueCapacity) {
        this.queryQueueCapacity = queryQueueCapacity;
    }

    public int getQueryMaxConcurrentBatches() {
        return queryMaxConcurrentBatches;
    }

    public void setQueryMaxConcurrentBatches(int queryMaxConcurrentBatches) {
        this.queryMaxConcurrentBatches = queryMaxConcurrentBatches;
    }

    public long getQueryWaitTimeoutMs() {
        return queryWaitTimeoutMs;
    }

    public void setQueryWaitTimeoutMs(long queryWaitTimeoutMs) {
        this.queryWaitTimeoutMs = queryWaitTimeoutMs;
    }
}
