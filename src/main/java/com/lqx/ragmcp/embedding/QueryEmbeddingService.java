package com.lqx.ragmcp.embedding;

/**
 * Query-side embedding boundary. Retrieval queries use this instead of {@link EmbeddingService}
 * so that concurrent queries can be coalesced into fewer Ollama calls and repeated queries can be
 * served from a bounded cache. Document ingestion keeps using {@link EmbeddingService} with its
 * original batch semantics.
 *
 * <p>The returned vector is validated (configured dimension, non-zero), is a defensive copy owned
 * by the caller, and every failure is surfaced as an {@code ApiException} - never as a zero vector
 * or an empty result. See docs/04-boundaries-and-errors.md section 4.</p>
 */
public interface QueryEmbeddingService {

    /**
     * Embeds one retrieval query. Implementations may batch concurrent calls, but must return the
     * vector for exactly this query and propagate {@code ERR-EMBEDDING_UNAVAILABLE} /
     * {@code ERR-EMBEDDING_TIMEOUT} instead of degrading silently.
     */
    float[] embedQuery(String query);

    /** Model identifier used in the cache key and for diagnostics. */
    String modelName();

    /** Expected vector dimension (1024 for bge-m3). */
    int dimension();
}
