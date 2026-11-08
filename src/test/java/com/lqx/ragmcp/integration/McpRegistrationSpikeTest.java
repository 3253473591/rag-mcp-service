package com.lqx.ragmcp.integration;

import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.McpSpikeConfig;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 MCP low-level registration spike (timeboxed). Not a production /mcp endpoint.
 * Verifies that two explicit SyncToolSpecification beans are auto-collected by Spring AI 1.1.8
 * even when spring.ai.mcp.server.tool-callback-converter=false, and appear in listTools()
 * (the server-side equivalent of tools/list).
 */
@Import(McpSpikeConfig.class)
class McpRegistrationSpikeTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mcpSpikeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.mcp.server.enabled", () -> "true");
        registry.add("spring.ai.mcp.server.tool-callback-converter", () -> "false");
        registry.add("spring.ai.mcp.server.protocol", () -> "STREAMABLE");
        registry.add("spring.ai.mcp.server.stdio", () -> "false");
        registry.add("spring.ai.mcp.server.name", () -> "rag-mcp-spike");
        registry.add("spring.ai.mcp.server.version", () -> "0.0.1-spike");
    }

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Test
    void twoLowLevelToolsAreRegisteredWhenToolCallbackConverterIsDisabled() {
        List<McpSchema.Tool> tools = mcpSyncServer.listTools();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("search_knowledge", "get_document");
    }
}
