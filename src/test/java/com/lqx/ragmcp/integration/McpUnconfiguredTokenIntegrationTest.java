package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.support.FakeEmbeddingConfig;
import com.lqx.ragmcp.support.McpTestDatabase;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code MCP_AUTH_TOKEN} unset the service must still start, but every {@code /mcp} request
 * must be rejected with 401 {@code ERR-MCP_UNAUTHORIZED} (fail closed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.mcp.server.enabled=true",
        "app.mcp.auth-token="
})
@Import(FakeEmbeddingConfig.class)
class McpUnconfiguredTokenIntegrationTest {

    static {
        McpTestDatabase.POSTGRES.isRunning();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", McpTestDatabase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", McpTestDatabase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", McpTestDatabase.POSTGRES::getPassword);
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void serviceStartsButRejectsEveryMcpRequest() throws Exception {
        // A missing token is a valid fail-closed configuration: the context and MCP server exist.
        assertThat(mcpSyncServer).isNotNull();

        HttpResponse<String> noToken = send(null);
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertUnauthorized(noToken);

        HttpResponse<String> anyToken = send("Bearer anything");
        assertThat(anyToken.statusCode()).isEqualTo(401);
        assertUnauthorized(anyToken);

        HttpResponse<String> getNoToken = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Accept", "text/event-stream")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(getNoToken.statusCode()).isEqualTo(401);
        assertUnauthorized(getNoToken);
    }

    private HttpResponse<String> send(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                          "protocolVersion":"2025-06-18","capabilities":{},
                          "clientInfo":{"name":"raw-http-test","version":"1.0.0"}}}
                        """));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertUnauthorized(HttpResponse<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo("ERR-MCP_UNAUTHORIZED");
        assertThat(body.get("traceId").asText()).isNotBlank();
    }
}
