package com.lqx.ragmcp.config;

import com.lqx.ragmcp.embedding.EmbeddingService;
import com.lqx.ragmcp.embedding.SpringAiEmbeddingService;
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
}
