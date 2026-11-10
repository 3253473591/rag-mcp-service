package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the Phase 11 LLM wiki configuration ({@code app.wiki.*}). */
@Configuration
@EnableConfigurationProperties(WikiProperties.class)
public class WikiConfig {
}
