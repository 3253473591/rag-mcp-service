package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers Phase 5 MCP configuration properties. */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {
}
