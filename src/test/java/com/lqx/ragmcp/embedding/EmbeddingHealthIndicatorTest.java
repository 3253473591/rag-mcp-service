package com.lqx.ragmcp.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingHealthIndicatorTest {

    @Test
    void matchesTaggedAndUntaggedOllamaModelNames() {
        assertThat(EmbeddingHealthIndicator.hasModel(
                "{\"models\":[{\"name\":\"bge-m3:latest\"}]}", "bge-m3")).isTrue();
        assertThat(EmbeddingHealthIndicator.hasModel(
                "{\"models\":[{\"name\":\"bge-m3\"}]}", "bge-m3")).isTrue();
        assertThat(EmbeddingHealthIndicator.hasModel(
                "{\"models\":[{\"name\":\"qwen3-vl:2b\"}]}", "bge-m3")).isFalse();
        assertThat(EmbeddingHealthIndicator.hasModel(null, "bge-m3")).isFalse();
    }
}
