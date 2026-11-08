package com.lqx.ragmcp.embedding;

import com.lqx.ragmcp.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual real-Ollama smoke test. NOT part of the default CI run (the suite uses the
 * deterministic fake EmbeddingService). Run with:
 *
 * <pre>
 *   $env:RAG_REAL_OLLAMA='true'; mvn test -Dtest=OllamaEmbeddingSmokeTest
 * </pre>
 *
 * Requires {@code ollama pull bge-m3} and Ollama on localhost:11434.
 */
@EnabledIfEnvironmentVariable(named = "RAG_REAL_OLLAMA", matches = "(?i)true|1")
class OllamaEmbeddingSmokeTest {

    @Test
    void realBgeM3Returns1024DimNonZeroVectors() {
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaEmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaEmbeddingOptions.builder().model("bge-m3").build())
                .build();

        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setModel("bge-m3");
        properties.setDimension(1024);
        properties.setBatchSize(16);
        properties.setRetries(0);
        properties.setTimeoutSeconds(120);
        EmbeddingService service = new SpringAiEmbeddingService(() -> embeddingModel, properties);

        List<float[]> vectors = service.embed(List.of(
                "高血压的诊断标准是什么？",
                "公司年假有多少天？"));

        assertThat(vectors).hasSize(2);
        for (float[] vector : vectors) {
            assertThat(vector).hasSize(1024);
            boolean nonZero = false;
            for (float value : vector) {
                if (value != 0f) {
                    nonZero = true;
                    break;
                }
            }
            assertThat(nonZero).isTrue();
        }
        assertThat(vectors.get(0)).isNotEqualTo(vectors.get(1));
    }
}
