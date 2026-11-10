package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Page layer configuration ({@code app.pages.*}), Phase 10.
 *
 * <p>A page is a deterministic aggregation of {@code chunk_strategy='heading'} chunks grouped by
 * {@code clause_path}. There is no LLM in this layer; the only knobs are the size limit that
 * decides when a page is split into parts and the response content truncation.</p>
 */
@ConfigurationProperties(prefix = "app.pages")
public class PageProperties {

    /**
     * Maximum body tokens of one page part. A leaf section whose chunks add up to more than this
     * is split at chunk boundaries into {@code part=1..n}; every part is its own row and gets its
     * own embedding. Default 1200 is above the heading chunker's 900-token section cap, so only
     * genuinely multi-chunk sections are split.
     */
    private int maxTokens = 1200;

    /** Response body truncation, matching POST /api/v1/search (2000 characters + ellipsis). */
    private int maxContentChars = 2000;

    /**
     * Aggregate pages synchronously after a successful upload. Aggregation is best effort: a
     * failure is logged and never fails the upload (see PageIngestionService). Rebuilding through
     * {@code POST /api/v1/admin/pages/rebuild} is the repair path.
     */
    private boolean ingestOnUpload = true;

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    public boolean isIngestOnUpload() {
        return ingestOnUpload;
    }

    public void setIngestOnUpload(boolean ingestOnUpload) {
        this.ingestOnUpload = ingestOnUpload;
    }
}
