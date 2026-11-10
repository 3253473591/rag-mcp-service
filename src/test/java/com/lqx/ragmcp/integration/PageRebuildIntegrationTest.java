package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.page.PageIngestionService;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin page rebuild contract (Phase 10): {@code X-Admin-Token} auth, idempotent delete+insert,
 * statistics endpoint, and the "heading chunks are the input" rule.
 */
@TestPropertySource(properties = "app.admin.token=test-admin-token")
class PageRebuildIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final AtomicInteger CHECKSUM = new AtomicInteger();

    @Autowired
    private PageIngestionService pageIngestionService;

    @Test
    void rebuildIsIdempotentAndRemovesStalePages() throws Exception {
        UUID docId = insertDocument("hypertension-guideline", "指南 2025", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(docId, "heading", 0, "诊断标准内容。", "1.1", "1 总则 > 1.1 诊断", 1);
        insertChunk(docId, "heading", 1, "测量方法内容。", "1.1", "1 总则 > 1.1 诊断", 2);
        insertChunk(docId, "heading", 2, "治疗原则内容。", "1.2", "1 总则 > 1.2 治疗", 2);

        JsonNode first = adminRebuild(Map.of("docIds", List.of(docId.toString())), 200);
        assertThat(first.get("documentCount").asInt()).isEqualTo(1);
        assertThat(first.get("pageCount").asInt()).isEqualTo(2);
        assertThat(first.get("chunkCount").asInt()).isEqualTo(3);
        assertThat(first.get("failures")).isEmpty();
        JsonNode detail = first.get("documents").get(0);
        assertThat(detail.get("docId").asText()).isEqualTo(docId.toString());
        assertThat(detail.get("pageGroupCount").asInt()).isEqualTo(2);
        assertThat(detail.get("pageCount").asInt()).isEqualTo(2);

        Map<String, Integer> keysAfterFirst = pageKeys(docId);

        // A stale row (simulating an older rule set) must be removed by a rebuild, not accumulate.
        insertPage(docId, "stale-key");
        assertThat(countPages(docId)).isEqualTo(3);

        JsonNode second = adminRebuild(Map.of("docIds", List.of(docId.toString())), 200);
        assertThat(second.get("pageCount").asInt()).isEqualTo(2);
        assertThat(countPages(docId)).isEqualTo(2);
        assertThat(pageKeys(docId)).isEqualTo(keysAfterFirst);
        assertThat(keysAfterFirst.values()).allMatch(count -> count == 1);
        assertThat(pageKeys(docId).keySet())
                .anyMatch(key -> key.endsWith("#1 总则 > 1.1 诊断"))
                .anyMatch(key -> key.endsWith("#1 总则 > 1.2 治疗"));
    }

    @Test
    void rebuildAndStatsRequireTheAdminToken() throws Exception {
        UUID docId = insertDocument("doc", "文档", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(docId, "heading", 0, "正文。", "1.1", "1 > 1.1", 1);

        mockMvc.perform(post("/api/v1/admin/pages/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ERR-ADMIN_UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/admin/pages/rebuild")
                        .header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/pages/stats"))
                .andExpect(status().isUnauthorized());

        JsonNode stats = objectMapper.readTree(mockMvc.perform(get("/api/v1/admin/pages/stats")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(stats.get("documentCount").asInt()).isEqualTo(1);
        assertThat(stats.get("pageCount").asInt()).isZero();
        assertThat(stats.get("documents").get(0).get("pageCount").asInt()).isZero();
    }

    @Test
    void rebuildAllBuildsHeadingDocumentsAndReportsZeroPagesForFixedOnly() throws Exception {
        UUID headingDoc = insertDocument("heading-doc", "有标题", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(headingDoc, "heading", 0, "标题正文。", "1.1", "1 > 1.1 小节", 1);
        // An upload with only the fixed strategy has no heading chunks, so it has no pages.
        JsonNode upload = upload(file("plain.txt", TestDocuments.txt()), Map.of(
                "docKey", "fixed-only",
                "effectiveFrom", "2025-01-01",
                "chunkStrategies", "fixed"), 201);
        UUID fixedDoc = UUID.fromString(upload.get("docId").asText());
        assertThat(countPages(fixedDoc)).isZero();

        JsonNode rebuilt = adminRebuild(Map.of(), 200);
        assertThat(rebuilt.get("documentCount").asInt()).isEqualTo(2);
        assertThat(rebuilt.get("pageCount").asInt()).isEqualTo(1);
        assertThat(rebuilt.get("documents")).hasSize(2);
        assertThat(rebuilt.get("documents").findValuesAsText("docId")).contains(
                headingDoc.toString(), fixedDoc.toString());
        assertThat(pageCountFor(rebuilt, fixedDoc)).isZero();
        assertThat(pageCountFor(rebuilt, headingDoc)).isEqualTo(1);
    }

    @Test
    void explicitDocIdsAreValidated() throws Exception {
        mockMvc.perform(post("/api/v1/admin/pages/rebuild")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docIds\":[\"not-a-uuid\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"))
                .andExpect(jsonPath("$.details.field").value("docIds"));
    }

    private JsonNode adminRebuild(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/pages/rebuild")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int countPages(UUID docId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_page WHERE document_id = ?::uuid", Integer.class,
                docId.toString());
        return count == null ? 0 : count;
    }

    private Map<String, Integer> pageKeys(UUID docId) {
        Map<String, Integer> keys = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT page_key, part FROM kb_page WHERE document_id = ?::uuid",
                rs -> {
                    keys.put(rs.getString("page_key"), rs.getInt("part"));
                }, docId.toString());
        return keys;
    }

    private static int pageCountFor(JsonNode response, UUID docId) {
        for (JsonNode document : response.get("documents")) {
            if (docId.toString().equals(document.get("docId").asText())) {
                return document.get("pageCount").asInt();
            }
        }
        throw new AssertionError("docId " + docId + " missing from rebuild response");
    }

    private void insertPage(UUID docId, String pageKey) {
        jdbc.update("""
                        INSERT INTO kb_page
                          (id, document_id, page_key, title, page_index, part, body, token_count,
                           embedding)
                        VALUES
                          (?::uuid, ?::uuid, ?, 'stale', 0, 1, 'stale body', 1, ?::vector)
                        """,
                UUID.randomUUID().toString(), docId.toString(), pageKey,
                vectorLiteral(FakeEmbeddingService.vectorFor("stale body")));
    }

    private UUID insertDocument(String docKey, String title, String version, String status,
                                LocalDate effectiveFrom, LocalDate effectiveTo) {
        UUID id = UUID.randomUUID();
        String checksum = String.format("%064x", CHECKSUM.incrementAndGet());
        jdbc.update("""
                        INSERT INTO kb_document
                          (id, doc_key, title, source_type, source_uri, checksum, version,
                           embedding_model, effective_from, effective_to, status, metadata)
                        VALUES
                          (?::uuid, ?, ?, 'MD', ?, ?, ?, 'bge-m3', ?::date, ?::date, ?, '{}'::jsonb)
                        """,
                id.toString(), docKey, title, "upload://test.md", checksum, version,
                effectiveFrom.toString(), effectiveTo == null ? null : effectiveTo.toString(),
                status);
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
}
