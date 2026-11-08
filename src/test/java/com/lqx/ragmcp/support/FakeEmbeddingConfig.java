package com.lqx.ragmcp.support;

import com.lqx.ragmcp.embedding.EmbeddingService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeEmbeddingConfig {

    @Bean
    @Primary
    public EmbeddingService fakeEmbeddingService() {
        return new FakeEmbeddingService();
    }
}
