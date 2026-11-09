package com.lqx.ragmcp.config;

import com.lqx.ragmcp.embedding.EmbeddingService;
import com.lqx.ragmcp.embedding.QueryEmbeddingBatcher;
import com.lqx.ragmcp.embedding.QueryEmbeddingService;
import com.lqx.ragmcp.embedding.SpringAiEmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, UploadProperties.class})
public class EmbeddingConfig {

    @Bean
    public EmbeddingService embeddingService(EmbeddingProperties properties,
                                             ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        return new SpringAiEmbeddingService(embeddingModelProvider::getIfAvailable, properties);
    }

    /**
     * Phase 6b: query-side batching/cache. Kept separate from {@link EmbeddingService} so document
     * ingestion keeps its original batch semantics and is never served from the query cache.
     */
    @Bean(destroyMethod = "close")
    public QueryEmbeddingService queryEmbeddingService(EmbeddingService embeddingService,
                                                       EmbeddingProperties properties,
                                                       MeterRegistry meterRegistry) {
        return new QueryEmbeddingBatcher(embeddingService, properties, meterRegistry);
    }
}
