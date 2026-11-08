package com.lqx.ragmcp.embedding;

import java.util.List;

/**
 * Replaceable embedding boundary. Production implementation delegates to a Spring AI
 * {@code EmbeddingModel} (Ollama bge-m3); tests use a deterministic fake.
 */
public interface EmbeddingService {

    /**
     * Embeds the given texts. The returned list has exactly the same size as the input,
     * and every vector has {@link #dimension()} dimensions and is non-zero.
     */
    List<float[]> embed(List<String> texts);

    /** Model identifier persisted on kb_document.embedding_model. */
    String modelName();

    /** Expected vector dimension (1024 for bge-m3). */
    int dimension();
}
