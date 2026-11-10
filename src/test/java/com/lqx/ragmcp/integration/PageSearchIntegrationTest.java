package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.page.PageIngestionService;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10 page layer against a real pgvector container:
 * <ul>
 *   <li>upload aggregates pages automatically and the page route returns citable hits;</li>
 *   <li>version / asOf / docIds filters are applied (in SQL) exactly like the chunk vector route;</li>
 *   <li>request validation reuses the chunk search error codes.</li>
 * </ul>
 */
class PageSearchIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger CHECKSUM = new AtomicInteger();
    private static final String AS_OF = "2026-11-09";

    @Autowired
    private PageIngestionService pageIngestionService;

    @Test
    void uploadAggregatesPagesAndPageSearchReturnsCitableResults() throws Exception {
        JsonNode upload = upload(file("handbook.md", TestDocuments.markdown()), Map.of(
                "docKey", "handbook",
                "title", "员工手册",
                "version", "2025",
                "effectiveFrom", "2025-01-01",
                "chunkStrategies", "heading"), 201);
        String docId = upload.get("docId").asText();

        Integer pageCount = jdbc.queryForObject(
                "SELECT count(*) FROM kb_page WHERE document_id = ?::uuid", Integer.class, docId);
        assertThat(pageCount).isNotNull().isGreaterThan(0);

        JsonNode response = pageSearch(Map.of(
                "query", "年假有几天",
                "topK", 5,
                "asOf", AS_OF,
                "docIds", List.of(docId)), 200);

        assertThat(response.get("query").asText()).isEqualTo("年假有几天");
        assertThat(response.get("asOf").asText()).isEqualTo(AS_OF);
        assertThat(response.get("topK").asInt()).isEqualTo(5);
        assertThat(response.get("results")).isNotEmpty();

        JsonNode first = response.get("results").get(0);
        assertThat(first.get("pageId").asText()).isNotBlank();
        assertThat(first.get("pageKey").asText()).startsWith("handbook@2025#");
        assertThat(first.get("title").asText()).isNotBlank();
        assertThat(first.get("clauseIds").isArray()).isTrue();
        assertThat(first.get("pageRange").has("pageNo")).isTrue();
        assertThat(first.get("pageRange").has("pageEnd")).isTrue();
        assertThat(first.get("part").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(first.get("pageIndex").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(first.get("tokenCount").asInt()).isGreaterThan(0);
        assertThat(first.get("score").asDouble()).isBetween(-1.0d, 1.0d);
        assertThat(first.get("content").asText()).isNotBlank();
        assertThat(first.get("citation").asText())
                .contains("《员工手册》")
                .contains("v2025")
                .contains("（2025-01-01 生效）");
        assertThat(first.get("document").get("docId").asText()).isEqualTo(docId);
        assertThat(first.get("document").get("docKey").asText()).isEqualTo("handbook");
        assertThat(first.get("document").get("docVersion").asText()).isEqualTo("2025");
        assertThat(first.get("document").get("docStatus").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("document").get("title").asText()).isEqualTo("员工手册");
    }

    @Test
    void pageSearchAppliesVersionAsOfDocIdsAndSupersededFilters() throws Exception {
        UUID oldDoc = insertDocument("hypertension-guideline", "指南 2024", "2024", "SUPERSEDED",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        insertChunk(oldDoc, "heading", 0, "旧版高血压诊断标准为 150/95。", "4.2",
                "第四章 > 4.2 诊断标准", 1);
        UUID newDoc = insertDocument("hypertension-guideline", "指南 2025", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(newDoc, "heading", 0, "新版高血压诊断标准为 140/90。", "4.2",
                "第四章 > 4.2 诊断标准", 4);
        UUID draftDoc = insertDocument("hypertension-guideline", "指南草稿", "2026", "DRAFT",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(draftDoc, "heading", 0, "草稿不可召回。", "4.2", "第四章 > 4.2 诊断标准", 1);
        pageIngestionService.rebuildDocument(oldDoc);
        pageIngestionService.rebuildDocument(newDoc);
        pageIngestionService.rebuildDocument(draftDoc);

        JsonNode current = pageSearch(Map.of(
                "query", "高血压", "topK", 20, "asOf", AS_OF), 200);
        assertThat(resultDocIds(current)).containsExactly(newDoc);

        JsonNode historical = pageSearch(Map.of(
                "query", "高血压", "topK", 20, "asOf", "2024-06-01"), 200);
        assertThat(resultDocIds(historical)).containsExactly(oldDoc);
        assertThat(historical.get("results").get(0).get("document").get("docStatus").asText())
                .isEqualTo("SUPERSEDED");

        JsonNode include = pageSearch(Map.of(
                "query", "高血压", "topK", 20, "asOf", AS_OF, "includeSuperseded", true), 200);
        assertThat(resultDocIds(include)).containsExactlyInAnyOrder(oldDoc, newDoc);
        assertThat(resultDocIds(include)).doesNotContain(draftDoc);

        JsonNode byVersion = pageSearch(Map.of(
                "query", "高血压", "topK", 20, "asOf", AS_OF, "version", "2025"), 200);
        assertThat(resultDocIds(byVersion)).containsExactly(newDoc);

        JsonNode byDocIds = pageSearch(Map.of(
                "query", "高血压", "topK", 20, "asOf", AS_OF,
                "docIds", List.of(newDoc.toString())), 200);
        assertThat(resultDocIds(byDocIds)).containsExactly(newDoc);
    }

    @Test
    void unknownQueryReturns200WithEmptyResults() throws Exception {
        JsonNode emptyDatabase = pageSearch(Map.of(
                "query", "不存在的问题", "topK", 5, "asOf", AS_OF), 200);
        assertThat(emptyDatabase.get("results")).isEmpty();

        UUID docId = insertDocument("none", "无命中", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(docId, "heading", 0, "完全无关的文本。", null, null, null);
        pageIngestionService.rebuildDocument(docId);

        JsonNode filtered = pageSearch(Map.of(
                "query", "不存在的问题", "topK", 5, "asOf", AS_OF,
                "docIds", List.of(UUID.randomUUID().toString())), 200);
        assertThat(filtered.get("results")).isEmpty();
    }

    @Test
    void pageSearchValidationReusesChunkRouteErrorCodes() throws Exception {
        pageSearchExpectError(Map.of("query", " "), 400, "ERR-VALIDATION", "query");
        pageSearchExpectError(Map.of("query", "q", "topK", 21), 400, "ERR-VALIDATION", "topK");
        pageSearchExpectError(Map.of("query", "q", "docIds", List.of("not-a-uuid")),
                400, "ERR-VALIDATION", "docIds");
        pageSearchExpectError(Map.of("query", "q", "asOf", "2024-6-1"),
                400, "ERR-INVALID_AS_OF", null);
        pageSearchExpectError(Map.of("query", "q", "asOf", "1899-12-31"),
                400, "ERR-INVALID_AS_OF", null);
    }

    @Test
    void emptyRequestBodyUsesChunkRouteContractError() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pages/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("ERR-VALIDATION");
    }

    private JsonNode pageSearch(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pages/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void pageSearchExpectError(Map<String, Object> body, int status, String code, String field)
            throws Exception {
        var request = post("/api/v1/pages/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        var expectation = mockMvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").exists());
        if (field != null) {
            expectation.andExpect(jsonPath("$.details.field").value(field));
        }
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

    private static List<UUID> resultDocIds(JsonNode response) {
        List<UUID> ids = new ArrayList<>();
        response.get("results").forEach(result ->
                ids.add(UUID.fromString(result.get("document").get("docId").asText())));
        return ids;
    }
}
