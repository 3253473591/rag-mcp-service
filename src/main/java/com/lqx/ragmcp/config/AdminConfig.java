package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers Phase 4 governance configuration properties. */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig {
}
