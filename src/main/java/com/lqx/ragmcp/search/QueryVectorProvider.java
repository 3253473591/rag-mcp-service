package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.embedding.QueryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Embeds a user query and enforces the embedding contract shared by the chunk and page search
 * routes: non-null result, exact dimension, and a non-zero vector.
 *
 * <p>Extracted from {@code SearchService} in Phase 10 so the page route cannot accidentally skip
 * the zero/dimension checks; the chunk route's behaviour is unchanged.</p>
 */
@Component
public class QueryVectorProvider {

    private static final Logger log = LoggerFactory.getLogger(QueryVectorProvider.class);

    private final QueryEmbeddingService queryEmbeddingService;

    public QueryVectorProvider(QueryEmbeddingService queryEmbeddingService) {
        this.queryEmbeddingService = queryEmbeddingService;
    }

    public float[] embedQuery(String query) {
        final float[] vector;
        try {
            vector = queryEmbeddingService.embedQuery(query);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("query embedding failed type={}", ex.getClass().getSimpleName());
            throw ApiException.embeddingUnavailable("embedding 服务不可用，请确认 Ollama 与 bge-m3 已就绪");
        }
        if (vector == null || vector.length != queryEmbeddingService.dimension()) {
            throw ApiException.embeddingUnavailable(
                    "embedding 维度不是 " + queryEmbeddingService.dimension() + "，请检查模型配置");
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (value != 0.0f) {
                nonZero = true;
                break;
            }
        }
        if (!nonZero) {
            throw ApiException.embeddingUnavailable("embedding 返回零向量，拒绝检索");
        }
        return vector;
    }
}
