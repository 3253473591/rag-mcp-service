package com.lqx.ragmcp.support;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only {@code @Tool}-annotated object. With
 * {@code spring.ai.mcp.server.tool-callback-converter=false} it must NOT appear in
 * {@code tools/list}; this is the guard against accidental {@code @Tool} auto-publishing.
 */
@TestConfiguration
public class McpTestToolsConfig {

    public static final String UNPUBLISHED_TOOL = "phase5_should_not_publish";

    @Bean
    public ToolCallbackProvider extraToolProvider() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new ExampleTools())
                .build();
    }

    /** A normal Spring AI tool object; the MCP server must not auto-publish it. */
    public static final class ExampleTools {

        @Tool(name = UNPUBLISHED_TOOL,
                description = "test-only tool; must not appear in tools/list")
        public String echo(String input) {
            return "this tool must never be published over MCP: " + input;
        }
    }
}
