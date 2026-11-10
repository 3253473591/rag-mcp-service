package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.TestDocuments;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end Phase 11 contract against a real pgvector container and a local DeepSeek stub:
 * compile writes {@code kind='wiki'} pages, a rerun is idempotent, and page search can select the
 * aggregate or wiki flavour with a SQL-side {@code kind} filter.
 */
@TestPropertySource(properties = {
        "app.admin.token=test-admin-token",
        "app.wiki.api-key=fake-key-for-stub",
        "app.wiki.max-attempts=1",
        "app.wiki.parse-retries=1"
})
class WikiCompileIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final String AS_OF = "2026-11-09";
    private static final HttpServer LLM = startStub();

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("app.wiki.base-url",
                () -> "http://127.0.0.1:" + LLM.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        LLM.stop(0);
    }

    @Test
    void compileWritesWikiPagesIdempotentlyAndKindFiltersSearch() throws Exception {
        String docId = upload(file("handbook.md", TestDocuments.markdown()), Map.of(
                "docKey", "handbook",
                "title", "员工手册",
                "version", "2025",
                "effectiveFrom", "2025-01-01",
                "chunkStrategies", "heading"), 201).get("docId").asText();
        int sections = headingSections(docId);
        assertThat(sections).isGreaterThan(0);

        JsonNode first = compile(Map.of("docIds", List.of(docId), "concurrency", 2), 200);
        assertThat(first.get("model").asText()).isEqualTo("deepseek-flash");
        assertThat(first.get("documentCount").asInt()).isEqualTo(1);
        assertThat(first.get("sectionCount").asInt()).isEqualTo(sections);
        assertThat(first.get("successCount").asInt()).isEqualTo(sections);
        assertThat(first.get("failedCount").asInt()).isZero();
        assertThat(first.get("skippedCount").asInt()).isZero();
        assertThat(first.get("totalTokens").asInt()).isEqualTo(sections * 18);
        assertThat(first.get("failures")).isEmpty();
        int pageCount = first.get("documents").get(0).get("pageCount").asInt();
        assertThat(pageCount).isEqualTo(sections);
        assertThat(countKind(docId, "wiki")).isEqualTo(pageCount);
        assertThat(countKind(docId, "aggregate")).isGreaterThan(0);

        JsonNode second = compile(Map.of("docIds", List.of(docId)), 200);
        assertThat(second.get("successCount").asInt()).isEqualTo(sections);
        assertThat(second.get("documents").get(0).get("pageCount").asInt()).isEqualTo(pageCount);
        assertThat(countKind(docId, "wiki")).isEqualTo(pageCount);

        JsonNode wiki = pageSearch(Map.of(
                "query", "年假", "topK", 5, "asOf", AS_OF, "docIds", List.of(docId),
                "kind", "wiki"), 200);
        assertThat(wiki.get("results")).isNotEmpty();
        wiki.get("results").forEach(result ->
                assertThat(result.get("content").asText()).contains("\"summary\""));

        JsonNode aggregate = pageSearch(Map.of(
                "query", "年假", "topK", 5, "asOf", AS_OF, "docIds", List.of(docId),
                "kind", "aggregate"), 200);
        assertThat(aggregate.get("results")).isNotEmpty();
        aggregate.get("results").forEach(result ->
                assertThat(result.get("content").asText()).doesNotContain("\"summary\""));

        JsonNode defaultKind = pageSearch(Map.of(
                "query", "年假", "topK", 5, "asOf", AS_OF, "docIds", List.of(docId)), 200);
        assertThat(resultPageIds(defaultKind)).isEqualTo(resultPageIds(aggregate));
        assertThat(resultPageIds(defaultKind)).doesNotContainAnyElementsOf(resultPageIds(wiki));

        pageSearchExpectError(Map.of("query", "年假", "kind", "deterministic"),
                400, "ERR-VALIDATION", "kind");
    }

    @Test
    void compileRequiresAdminTokenAndValidatesConcurrency() throws Exception {
        mockMvc.perform(post("/api/v1/admin/pages/compile-wiki")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ERR-ADMIN_UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/admin/pages/compile-wiki")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concurrency\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"))
                .andExpect(jsonPath("$.details.field").value("concurrency"));
    }

    private JsonNode compile(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/pages/compile-wiki")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode pageSearch(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pages/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void pageSearchExpectError(Map<String, Object> body, int status, String code,
                                       String field) throws Exception {
        mockMvc.perform(post("/api/v1/pages/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.details.field").value(field));
    }

    private int countKind(String docId, String kind) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_page WHERE document_id = ?::uuid AND kind = ?",
                Integer.class, docId, kind);
        return count == null ? 0 : count;
    }

    private int headingSections(String docId) {
        Integer count = jdbc.queryForObject("""
                        SELECT count(DISTINCT COALESCE(clause_path, '__root__'))
                        FROM kb_chunk
                        WHERE document_id = ?::uuid AND chunk_strategy = 'heading'
                        """, Integer.class, docId);
        return count == null ? 0 : count;
    }

    private static List<String> resultPageIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.get("results").forEach(result -> ids.add(result.get("pageId").asText()));
        return ids;
    }

    private static HttpServer startStub() {
        String wiki = "{\"title\":\"测试页面\",\"summary\":\"摘要\",\"key_points\":[\"要点\"],"
                + "\"cross_refs\":[],\"sources\":[]}";
        String envelope = "{\"choices\":[{\"message\":{\"content\":"
                + quote(wiki) + "}}],\"usage\":{\"prompt_tokens\":11,"
                + "\"completion_tokens\":7,\"total_tokens\":18}}";
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
