package com.lqx.ragmcp.support;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

/**
 * Phase 2 MCP registration spike fixture: register only two low-level SyncToolSpecification
 * entries through a List bean while spring.ai.mcp.server.tool-callback-converter=false.
 *
 * <p>Finding: Spring AI 1.1.8's McpServerAutoConfiguration streams the injected
 * {@code ObjectProvider<List<SyncToolSpecification>>} and flat-maps each List bean. Registering
 * two individual SyncToolSpecification beans is NOT enough; the app must expose a
 * {@code List<SyncToolSpecification>} bean.</p>
 */
@TestConfiguration
public class McpSpikeConfig {

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpSpikeTools() {
        return List.of(
                spec("search_knowledge", "spike only: search knowledge", List.of("mandateId", "query")),
                spec("get_document", "spike only: get document metadata", List.of("mandateId", "docId")));
    }

    private static McpServerFeatures.SyncToolSpecification spec(String name, String description,
                                                                List<String> required) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema(required))
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool,
                (exchange, args) -> new McpSchema.CallToolResult("ok", false));
    }

    private static McpSchema.JsonSchema schema(List<String> required) {
        Map<String, Object> properties = Map.of(
                "mandateId", Map.of("type", "string", "format", "uuid"),
                "query", Map.of("type", "string", "maxLength", 500),
                "docId", Map.of("type", "string", "format", "uuid"));
        return new McpSchema.JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }
}
