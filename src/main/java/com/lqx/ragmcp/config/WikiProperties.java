package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM wiki compilation configuration ({@code app.wiki.*}), Phase 11.
 *
 * <p><b>Secret handling.</b> {@link #apiKey} is bound only from the {@code DEEPSEEK_API_KEY}
 * environment variable. It is never written to a file, never logged, and never echoed in an
 * error message. When it is missing the compiler fails closed with
 * {@code ERR-LLM_NOT_CONFIGURED}; it never falls back to synthetic pages.</p>
 *
 * <p>Retry policy is split in two: transport retries ({@link #maxAttempts}, exponential
 * backoff, honours {@code Retry-After}) happen inside the DeepSeek client, while one
 * <em>parse</em> retry ({@link #parseRetries}) happens in the section compiler when the model
 * returns something that is not the required JSON object.</p>
 */
@ConfigurationProperties(prefix = "app.wiki")
public class WikiProperties {

    /** DeepSeek API key, bound from DEEPSEEK_API_KEY. Blank = feature disabled (fail closed). */
    private String apiKey = "";

    /** OpenAI-compatible base URL. Default is the public DeepSeek API. */
    private String baseUrl = "https://api.deepseek.com";

    /** Model id persisted in {@code kb_page.llm_model}. */
    private String model = "deepseek-flash";

    /** Output budget per call. The model reasons before answering, so this must be generous. */
    private int maxTokens = 8192;

    /** Sampling temperature. Low for extraction, not zero to avoid degenerate repetition. */
    private double temperature = 0.2d;

    /** Per-request timeout (connect + response). */
    private int timeoutSeconds = 120;

    /** Transport attempts (1 = no retry). 429 and 5xx are retried with exponential backoff. */
    private int maxAttempts = 3;

    /** Base backoff for transport retries; doubled after each failed attempt. */
    private long retryBackoffMs = 1000L;

    /** Parse retries for a 200 response whose body is not the required JSON object. */
    private int parseRetries = 1;

    /** Default compile concurrency (overridable per request, clamped to 1..16). */
    private int concurrency = 4;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens <= 0 ? 8192 : maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
    }

    public int getParseRetries() {
        return parseRetries;
    }

    public void setParseRetries(int parseRetries) {
        this.parseRetries = Math.max(0, parseRetries);
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency <= 0 ? 4 : Math.min(concurrency, 16);
    }
}
