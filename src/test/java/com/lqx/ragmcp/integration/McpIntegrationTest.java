package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.mcp.McpAuth;
import com.lqx.ragmcp.support.FakeEmbeddingConfig;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import com.lqx.ragmcp.support.McpTestDatabase;
import com.lqx.ragmcp.support.McpTestToolsConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 5 production MCP evidence: real Streamable HTTP client (initialize/tools/list/tools/call),
 * tools/list guard, Bearer 401, mandate matrix, document metadata, audit rows and JSON-RPC errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.mcp.server.enabled=true",
        "app.mcp.auth-token=test-mcp-token",
        "app.admin.token=test-admin-token"
})
@Import({FakeEmbeddingConfig.class, McpTestToolsConfig.class})
class McpIntegrationTest {

    private static final String MCP_TOKEN = "test-mcp-token";
    private static final AtomicInteger CHECKSUM = new AtomicInteger();

    static {
        // Start the shared container before Spring's dynamic properties are read.
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
    private JdbcTemplate jdbc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE kb_chunk, kb_document, mandate, work_item, mcp_audit_log "
                + "RESTART IDENTITY CASCADE");
        FakeEmbeddingService.FAIL_NEXT.set(false);
    }

    // ---------------------------------------------------------------------------------------
    // tools/list + negotiation
    // ---------------------------------------------------------------------------------------

    @Test
    void initializeNegotiatesProtocolAndListsExactlyTwoTools() {
        try (McpSyncClient client = newClient()) {
            McpSchema.InitializeResult init = client.initialize();

            System.out.println("MCP negotiated protocolVersion=" + init.protocolVersion()
                    + " server=" + init.serverInfo().name() + "/" + init.serverInfo().version());
            assertThat(init.protocolVersion()).isNotBlank();
            assertThat(init.serverInfo().name()).isEqualTo("rag-mcp-service");
            assertThat(init.capabilities().tools()).isNotNull();
            assertThat(init.capabilities().resources()).isNull();
            assertThat(init.capabilities().prompts()).isNull();
            assertThat(init.capabilities().completions()).isNull();

            List<String> overHttp = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(overHttp).containsExactlyInAnyOrder("search_knowledge", "get_document");
            assertThat(overHttp).doesNotContain(McpTestToolsConfig.UNPUBLISHED_TOOL);
        }

        List<String> serverSide = mcpSyncServer.listTools().stream()
                .map(McpSchema.Tool::name).toList();
        assertThat(serverSide).containsExactlyInAnyOrder("search_knowledge", "get_document");
        assertThat(serverSide).doesNotContain(McpTestToolsConfig.UNPUBLISHED_TOOL);
    }

    // ---------------------------------------------------------------------------------------
    // Bearer authentication
    // ---------------------------------------------------------------------------------------

    @Test
    void olderProtocolVersion20250618IsNegotiatedWhenRequested() {
        try (McpSyncClient client = newClient("2025-06-18")) {
            McpSchema.InitializeResult init = client.initialize();
            assertThat(init.protocolVersion()).isEqualTo("2025-06-18");
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("search_knowledge", "get_document");
        }
    }

    @Test
    void everyMcpRequestRequiresTheBearerToken() throws Exception {
        HttpResponse<String> missing = postRaw(initializeBody(), null);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertUnauthorizedBody(missing);
        assertThat(missing.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(value -> assertThat(value).contains("Bearer"));

        HttpResponse<String> wrong = postRaw(initializeBody(), "Bearer wrong-token");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertUnauthorizedBody(wrong);

        HttpResponse<String> adminTokenIsNotAccepted = postRaw(initializeBody(),
                "Bearer test-admin-token");
        assertThat(adminTokenIsNotAccepted.statusCode()).isEqualTo(401);

        HttpResponse<String> getWithoutToken = getRaw(null);
        assertThat(getWithoutToken.statusCode()).isEqualTo(401);
        assertUnauthorizedBody(getWithoutToken);

        // Encoded spelling must not bypass the gate (the decoded path is /mcp).
        HttpResponse<String> encodedPath = postRawPath("/m%63p", initializeBody(), null);
        assertThat(encodedPath.statusCode()).isEqualTo(401);
        assertUnauthorizedBody(encodedPath);
    }

    // ---------------------------------------------------------------------------------------
    // mandate matrix
    // ---------------------------------------------------------------------------------------

    @Test
    void missingOrUnknownMandateIdReturnsRequired() {
        try (McpSyncClient client = newClient()) {
            client.initialize();

            assertToolError(callSearch(client, Map.of("query", "高血压")),
                    "ERR-MANDATE_REQUIRED");
            assertToolError(callSearch(client, Map.of(
                            "mandateId", UUID.randomUUID().toString(),
                            "query", "高血压")),
                    "ERR-MANDATE_REQUIRED");
        }
    }

    @Test
    void expiredOrRevokedMandateReturnsExpired() {
        UUID docId = insertDocument("doc-a", "A", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docId, "heading", 0, "高血压诊断标准 A", "3.2", "第三章", 1);

        MandateFixture expired = createMandate(List.of("search_knowledge"), List.of(docId), 5,
                OffsetDateTime.now().minusMinutes(5), "ACTIVE");
        MandateFixture revoked = createMandate(List.of("search_knowledge"), List.of(docId), 5,
                OffsetDateTime.now().plusHours(1), "REVOKED");

        try (McpSyncClient client = newClient()) {
            client.initialize();
            assertToolError(callSearch(client, Map.of(
                    "mandateId", expired.mandateId().toString(),
                    "query", "高血压")), "ERR-MANDATE_EXPIRED");
            assertToolError(callSearch(client, Map.of(
                    "mandateId", revoked.mandateId().toString(),
                    "query", "高血压")), "ERR-MANDATE_EXPIRED");
        }
    }

    @Test
    void toolAboveMaxTopKAndOutsideDocScopeReturnScope() {
        UUID docA = insertDocument("doc-a", "A", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        UUID docB = insertDocument("doc-b", "B", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docA, "heading", 0, "高血压 A", "3.2", "第三章", 1);
        insertChunk(docB, "heading", 0, "高血压 B", "3.2", "第三章", 1);

        MandateFixture searchOnlyForA = createMandate(
                List.of("search_knowledge"), List.of(docA), 3);
        MandateFixture documentOnly = createMandate(
                List.of("get_document"), List.of(docA), 5);

        try (McpSyncClient client = newClient()) {
            client.initialize();

            assertToolError(callSearch(client, Map.of(
                    "mandateId", searchOnlyForA.mandateId().toString(),
                    "query", "高血压",
                    "topK", 5)), "ERR-MANDATE_SCOPE");
            assertToolError(callSearch(client, Map.of(
                    "mandateId", searchOnlyForA.mandateId().toString(),
                    "query", "高血压",
                    "docIds", List.of(docB.toString()))), "ERR-MANDATE_SCOPE");
            assertToolError(callSearch(client, Map.of(
                    "mandateId", documentOnly.mandateId().toString(),
                    "query", "高血压")), "ERR-MANDATE_SCOPE");
            assertToolError(callGetDocument(client, Map.of(
                    "mandateId", searchOnlyForA.mandateId().toString(),
                    "docId", docB.toString())), "ERR-MANDATE_SCOPE");
        }
    }

    @Test
    void missingDocIdsUsesMandateAllowedDocIdsAsSqlFilter() {
        UUID docA = insertDocument("doc-a", "A 指南", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        UUID docB = insertDocument("doc-b", "B 指南", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docA, "heading", 0, "高血压诊断标准来自 A 指南。", "3.2", "第三章", 1);
        insertChunk(docB, "heading", 0, "高血压诊断标准来自 B 指南。", "3.2", "第三章", 1);

        MandateFixture fixture = createMandate(
                List.of("search_knowledge"), List.of(docA), 5);

        try (McpSyncClient client = newClient()) {
            client.initialize();
            JsonNode body = successBody(callSearch(client, Map.of(
                    "mandateId", fixture.mandateId().toString(),
                    "query", "高血压",
                    "topK", 5)));

            assertThat(body.get("results")).isNotEmpty();
            for (JsonNode result : body.get("results")) {
                assertThat(result.get("docId").asText()).isEqualTo(docA.toString());
                assertThat(result.get("content").asText()).doesNotContain("B 指南");
            }
        }
    }

    @Test
    void emptyAllowedDocIdsReturnsNoDocumentsWithoutLeaking() {
        UUID docA = insertDocument("doc-a", "A 指南", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docA, "heading", 0, "高血压诊断标准来自 A 指南。", "3.2", "第三章", 1);

        MandateFixture emptyScope = createMandate(
                List.of("search_knowledge", "get_document"), List.of(), 5);

        try (McpSyncClient client = newClient()) {
            client.initialize();

            JsonNode body = successBody(callSearch(client, Map.of(
                    "mandateId", emptyScope.mandateId().toString(),
                    "query", "高血压")));
            assertThat(body.get("results")).isEmpty();
            assertThat(body.get("conflicts")).isEmpty();

            assertToolError(callGetDocument(client, Map.of(
                    "mandateId", emptyScope.mandateId().toString(),
                    "docId", docA.toString())), "ERR-MANDATE_SCOPE");
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, error_code FROM mcp_audit_log ORDER BY id DESC LIMIT 1");
        assertThat(row.get("status")).isEqualTo("ERROR");
        assertThat(row.get("error_code")).isEqualTo("ERR-MANDATE_SCOPE");
    }

    @Test
    void searchReturnsAtMostTenResults() {
        UUID docId = insertDocument("doc-many", "很多片段", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1));
        for (int i = 0; i < 12; i++) {
            insertChunk(docId, "heading", i, "高血压相关片段 " + i + " 内容。", null, null, i + 1);
        }
        MandateFixture fixture = createMandate(
                List.of("search_knowledge"), List.of(docId), 20);

        try (McpSyncClient client = newClient()) {
            client.initialize();
            JsonNode body = successBody(callSearch(client, Map.of(
                    "mandateId", fixture.mandateId().toString(),
                    "query", "高血压",
                    "topK", 20)));
            assertThat(body.get("results")).hasSize(10);
        }
    }

    @Test
    void getDocumentReturnsMetadataOnly() {
        UUID docId = insertDocument("doc-a", "国家基层高血压防治管理指南2025版", "2025",
                "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docId, "heading", 0, "高血压诊断标准。", "3.2", "第三章", 1);
        insertChunk(docId, "heading", 1, "生活方式干预。", "5.1", "第五章", 2);

        MandateFixture fixture = createMandate(
                List.of("get_document"), List.of(docId), 5);

        try (McpSyncClient client = newClient()) {
            client.initialize();
            JsonNode body = successBody(callGetDocument(client, Map.of(
                    "mandateId", fixture.mandateId().toString(),
                    "docId", docId.toString())));

            Set<String> fields = new HashSet<>();
            body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder(
                    "docId", "docKey", "title", "sourceType", "version", "status",
                    "effectiveFrom", "effectiveTo", "chunkCount", "createdAt");
            assertThat(body.get("docId").asText()).isEqualTo(docId.toString());
            assertThat(body.get("title").asText()).isEqualTo("国家基层高血压防治管理指南2025版");
            assertThat(body.get("sourceType").asText()).isEqualTo("MD");
            assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(body.get("chunkCount").asInt()).isEqualTo(2);
            assertThat(body.get("createdAt").asText()).isNotBlank();
            assertThat(body.has("content")).isFalse();
            assertThat(body.has("sourceUri")).isFalse();
            assertThat(body.has("checksum")).isFalse();
        }
    }

    @Test
    void getDocumentOutOfScopeAndNotFoundPaths() {
        UUID docId = insertDocument("doc-a", "A", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docId, "heading", 0, "内容", null, null, 1);

        MandateFixture allDocs = createMandate(
                List.of("get_document"), null, 5);
        MandateFixture onlyA = createMandate(
                List.of("get_document"), List.of(docId), 5);

        try (McpSyncClient client = newClient()) {
            client.initialize();
            assertToolError(callGetDocument(client, Map.of(
                    "mandateId", allDocs.mandateId().toString(),
                    "docId", UUID.randomUUID().toString())), "ERR-DOC_NOT_FOUND");
            assertToolError(callGetDocument(client, Map.of(
                    "mandateId", onlyA.mandateId().toString(),
                    "docId", UUID.randomUUID().toString())), "ERR-MANDATE_SCOPE");
        }
    }

    // ---------------------------------------------------------------------------------------
    // JSON-RPC errors
    // ---------------------------------------------------------------------------------------

    @Test
    void unknownToolReturnsJsonRpcMethodNotFound() {
        try (McpSyncClient client = newClient()) {
            client.initialize();

            McpError error = catchMcpError(() -> client.callTool(
                    new McpSchema.CallToolRequest("delete_documents", Map.of())));
            assertThat(error.getJsonRpcError().code()).isEqualTo(-32601);
            assertThat(error.getJsonRpcError().message()).contains("delete_documents");
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tool_name, status, error_code FROM mcp_audit_log ORDER BY id DESC LIMIT 1");
        assertThat(row.get("tool_name")).isEqualTo("delete_documents");
        assertThat(row.get("status")).isEqualTo("ERROR");
        assertThat(row.get("error_code")).isEqualTo("-32601");
    }

    @Test
    void malformedArgumentsReturnJsonRpcInvalidParams() {
        UUID docId = insertDocument("doc-a", "A", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docId, "heading", 0, "内容", null, null, 1);
        MandateFixture fixture = createMandate(
                List.of("search_knowledge", "get_document"), List.of(docId), 5);

        try (McpSyncClient client = newClient()) {
            client.initialize();

            McpError missingQuery = catchMcpError(() -> callSearch(client, Map.of(
                    "mandateId", fixture.mandateId().toString())));
            assertThat(missingQuery.getJsonRpcError().code()).isEqualTo(-32602);

            McpError badTopK = catchMcpError(() -> callSearch(client, Map.of(
                    "mandateId", fixture.mandateId().toString(),
                    "query", "q",
                    "topK", 21)));
            assertThat(badTopK.getJsonRpcError().code()).isEqualTo(-32602);

            McpError badDocIds = catchMcpError(() -> callSearch(client, Map.of(
                    "mandateId", fixture.mandateId().toString(),
                    "query", "q",
                    "docIds", List.of("not-a-uuid"))));
            assertThat(badDocIds.getJsonRpcError().code()).isEqualTo(-32602);

            McpError badDocId = catchMcpError(() -> callGetDocument(client, Map.of(
                    "mandateId", fixture.mandateId().toString(),
                    "docId", "not-a-uuid")));
            assertThat(badDocId.getJsonRpcError().code()).isEqualTo(-32602);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Audit
    // ---------------------------------------------------------------------------------------

    @Test
    void auditContainsRequiredFieldsButNeverTheQueryText() {
        UUID docId = insertDocument("doc-a", "A", "2025", "ACTIVE", LocalDate.of(2025, 1, 1));
        insertChunk(docId, "heading", 0, "高血压诊断标准。", "3.2", "第三章", 1);

        String successQuery = "审计机密查询词-成功-" + UUID.randomUUID();
        String expiredQuery = "审计机密查询词-过期-" + UUID.randomUUID();
        String invalidQuery = "审计机密查询词-参数-" + UUID.randomUUID();

        MandateFixture active = createMandate(
                List.of("search_knowledge"), List.of(docId), 5);
        MandateFixture expired = createMandate(
                List.of("search_knowledge"), List.of(docId), 5,
                OffsetDateTime.now().minusMinutes(1), "ACTIVE");

        try (McpSyncClient client = newClient()) {
            client.initialize();

            assertThat(successBody(callSearch(client, Map.of(
                    "mandateId", active.mandateId().toString(),
                    "query", successQuery))).get("results")).isNotNull();

            assertToolError(callSearch(client, Map.of(
                    "mandateId", expired.mandateId().toString(),
                    "query", expiredQuery)), "ERR-MANDATE_EXPIRED");

            McpError invalid = catchMcpError(() -> callSearch(client, Map.of(
                    "mandateId", active.mandateId().toString(),
                    "query", invalidQuery,
                    "topK", 21)));
            assertThat(invalid.getJsonRpcError().code()).isEqualTo(-32602);
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tool_name, work_item_id::text AS work_item_id,
                       mandate_id::text AS mandate_id, caller_fingerprint, args_hash,
                       status, error_code, latency_ms
                FROM mcp_audit_log
                ORDER BY id
                """);
        assertThat(rows).hasSize(3);
        assertThat(rows.toString())
                .doesNotContain(successQuery)
                .doesNotContain(expiredQuery)
                .doesNotContain(invalidQuery);

        Map<String, Object> ok = findRow(rows, "OK");
        assertThat(ok.get("tool_name")).isEqualTo("search_knowledge");
        assertThat(ok.get("work_item_id")).isEqualTo(active.workItemId().toString());
        assertThat(ok.get("mandate_id")).isEqualTo(active.mandateId().toString());
        assertThat(ok.get("caller_fingerprint")).isEqualTo(McpAuth.fingerprint(MCP_TOKEN));
        assertThat((String) ok.get("args_hash")).matches("[0-9a-f]{64}");
        assertThat(ok.get("error_code")).isNull();
        assertThat(((Number) ok.get("latency_ms")).intValue()).isGreaterThanOrEqualTo(0);

        Map<String, Object> expiredRow = findRowByError(rows, "ERR-MANDATE_EXPIRED");
        assertThat(expiredRow.get("work_item_id")).isEqualTo(expired.workItemId().toString());
        assertThat(expiredRow.get("mandate_id")).isEqualTo(expired.mandateId().toString());
        assertThat(expiredRow.get("status")).isEqualTo("ERROR");

        Map<String, Object> invalidRow = findRowByError(rows, "-32602");
        assertThat(invalidRow.get("status")).isEqualTo("ERROR");
        assertThat(invalidRow.get("mandate_id")).isEqualTo(active.mandateId().toString());
    }

    // ---------------------------------------------------------------------------------------
    // Tools and helpers
    // ---------------------------------------------------------------------------------------

    private McpSyncClient newClient(String... supportedProtocolVersions) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .customizeRequest(request -> request.header(
                        "Authorization", "Bearer " + MCP_TOKEN));
        if (supportedProtocolVersions.length > 0) {
            builder.supportedProtocolVersions(List.of(supportedProtocolVersions));
        }
        return McpClient.sync(builder.build())
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("phase5-test-client", "1.0.0"))
                .build();
    }

    private McpSchema.CallToolResult callSearch(McpSyncClient client, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest("search_knowledge", arguments));
    }

    private McpSchema.CallToolResult callGetDocument(McpSyncClient client, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest("get_document", arguments));
    }

    private static McpError catchMcpError(ThrowingCallable callable) {
        Throwable thrown = catchThrowable(callable::call);
        assertThat(thrown).isInstanceOf(McpError.class);
        return (McpError) thrown;
    }

    private JsonNode successBody(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isFalse();
        try {
            return objectMapper.readTree(toolText(result));
        } catch (Exception ex) {
            throw new AssertionError("tool result is not JSON: " + toolText(result), ex);
        }
    }

    private void assertToolError(McpSchema.CallToolResult result, String expectedCode) {
        assertThat(result.isError()).isTrue();
        try {
            JsonNode body = objectMapper.readTree(toolText(result));
            assertThat(body.get("code").asText()).isEqualTo(expectedCode);
            assertThat(body.get("message").asText()).isNotBlank();
        } catch (Exception ex) {
            throw new AssertionError("tool error is not JSON: " + toolText(result), ex);
        }
    }

    private static String toolText(McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotNull().isNotEmpty();
        assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private HttpResponse<String> postRaw(String body, String authorization) throws Exception {
        return postRawPath("/mcp", body, authorization);
    }

    private HttpResponse<String> postRawPath(String path, String body, String authorization)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getRaw(String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Accept", "text/event-stream");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private void assertUnauthorizedBody(HttpResponse<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo("ERR-MCP_UNAUTHORIZED");
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("traceId").asText()).isNotBlank();
    }

    private static String initializeBody() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18","capabilities":{},
                  "clientInfo":{"name":"raw-http-test","version":"1.0.0"}}}
                """;
    }

    private static Map<String, Object> findRow(List<Map<String, Object>> rows, String status) {
        return rows.stream().filter(row -> status.equals(row.get("status"))).findFirst()
                .orElseThrow(() -> new AssertionError("no audit row with status " + status));
    }

    private static Map<String, Object> findRowByError(List<Map<String, Object>> rows, String code) {
        return rows.stream().filter(row -> code.equals(row.get("error_code"))).findFirst()
                .orElseThrow(() -> new AssertionError("no audit row with error " + code));
    }

    // ---------------------------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------------------------

    private UUID insertDocument(String docKey, String title, String version, String status,
                                LocalDate effectiveFrom) {
        UUID id = UUID.randomUUID();
        String checksum = String.format("%064x", CHECKSUM.incrementAndGet());
        jdbc.update("""
                        INSERT INTO kb_document
                          (id, doc_key, title, source_type, source_uri, checksum, version,
                           embedding_model, effective_from, effective_to, status, metadata)
                        VALUES
                          (?::uuid, ?, ?, 'MD', ?, ?, ?, 'bge-m3', ?::date, NULL, ?, '{}'::jsonb)
                        """,
                id.toString(), docKey, title, "upload://" + docKey + ".md", checksum, version,
                effectiveFrom.toString(), status);
        return id;
    }

    private UUID insertChunk(UUID docId, String strategy, int chunkIndex, String content,
                             String clauseId, String clausePath, Integer pageNo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO kb_chunk
                          (id, document_id, chunk_index, chunk_strategy, content, token_count,
                           clause_id, clause_path, page_no, metadata, embedding)
                        VALUES
                          (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?::vector)
                        """,
                id.toString(), docId.toString(), chunkIndex, strategy, content,
                Math.max(1, content.length() / 2), clauseId, clausePath, pageNo,
                vectorLiteral(FakeEmbeddingService.vectorFor(content)));
        return id;
    }

    private MandateFixture createMandate(List<String> allowedTools, List<UUID> allowedDocIds,
                                         int maxTopK) {
        return createMandate(allowedTools, allowedDocIds, maxTopK,
                OffsetDateTime.now().plusHours(1), "ACTIVE");
    }

    private MandateFixture createMandate(List<String> allowedTools, List<UUID> allowedDocIds,
                                         int maxTopK, OffsetDateTime expiresAt, String status) {
        UUID workItemId = UUID.randomUUID();
        UUID mandateId = UUID.randomUUID();
        String toolsJson = toJson(allowedTools);
        String docIdsJson = allowedDocIds == null ? null : toJson(
                allowedDocIds.stream().map(UUID::toString).toList());

        jdbc.update("""
                        INSERT INTO work_item
                          (id, requester, purpose, requested_tools, requested_doc_ids,
                           requested_top_k, status)
                        VALUES
                          (?::uuid, 'phase5-test', 'MCP integration test',
                           ?::jsonb, ?::jsonb, ?, 'APPROVED')
                        """,
                workItemId.toString(), toolsJson, docIdsJson, maxTopK);
        jdbc.update("""
                        INSERT INTO mandate
                          (id, work_item_id, requester, allowed_tools, allowed_doc_ids,
                           max_top_k, status, expires_at)
                        VALUES
                          (?::uuid, ?::uuid, 'phase5-test', ?::jsonb, ?::jsonb, ?, ?, ?::timestamptz)
                        """,
                mandateId.toString(), workItemId.toString(), toolsJson, docIdsJson,
                maxTopK, status, expiresAt.toString());
        return new MandateFixture(workItemId, mandateId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(vector[i]));
        }
        return builder.append(']').toString();
    }

    private record MandateFixture(UUID workItemId, UUID mandateId) {
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}
