package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the Phase 10 page layer configuration ({@code app.pages.*}). */
@Configuration
@EnableConfigurationProperties(PageProperties.class)
public class PageConfig {
}
