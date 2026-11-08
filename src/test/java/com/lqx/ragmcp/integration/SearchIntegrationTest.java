package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import org.junit.jupiter.api.Test;
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
 * T-INT-SEARCH-01..05 and T-VERSION-02/03/06/07/11 against a real pgvector container and a
 * deterministic fake EmbeddingService.
 */
class SearchIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger CHECKSUM = new AtomicInteger();

    @Test
    void tIntSearch01And03_hybridReturnsStructuredResultsWithRrfScoreAndCitation() throws Exception {
        UUID docId = insertDocument("hypertension-guideline", "国家基层高血压防治管理指南2025版",
                "2025", "ACTIVE", LocalDate.of(2025, 1, 1), null);
        UUID chunkId = insertChunk(docId, "heading", 0,
                "高血压的诊断标准是诊室血压大于等于140/90 mmHg。", "3.2",
                "第三章 > 3.2 诊断标准", 12);
        insertChunk(docId, "heading", 1, "生活方式干预是高血压治疗的基础。", null,
                "第五章 > 5.1 治疗", 20);

        JsonNode response = search(Map.of(
                "query", "高血压",
                "topK", 5,
                "asOf", "2026-11-09",
                "chunkStrategy", "heading",
                "strategy", "hybrid"), 200);

        assertThat(response.get("query").asText()).isEqualTo("高血压");
        assertThat(response.get("asOf").asText()).isEqualTo("2026-11-09");
        assertThat(response.get("chunkStrategy").asText()).isEqualTo("heading");
        assertThat(response.get("strategy").asText()).isEqualTo("hybrid");
        assertThat(response.get("tookMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(response.get("conflicts").isArray()).isTrue();
        assertThat(response.get("conflicts")).isEmpty();
        assertThat(response.get("results")).isNotEmpty();

        JsonNode first = response.get("results").get(0);
        assertThat(first.get("chunkId").asText()).isNotBlank();
        assertThat(first.get("docId").asText()).isEqualTo(docId.toString());
        assertThat(first.get("docKey").asText()).isEqualTo("hypertension-guideline");
        assertThat(first.get("docVersion").asText()).isEqualTo("2025");
        assertThat(first.get("docStatus").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("effectiveFrom").asText()).isEqualTo("2025-01-01");
        assertThat(first.get("effectiveTo").isNull()).isTrue();
        assertThat(first.get("title").asText()).isEqualTo("国家基层高血压防治管理指南2025版");
        assertThat(first.get("pageNo").asInt()).isBetween(1, 100);
        assertThat(first.get("score").asDouble()).isBetween(1.0d / 61.0d - 1e-12, 2.0d / 61.0d + 1e-12);
        assertThat(first.get("content").asText()).isNotBlank();
        assertThat(first.get("citation").asText())
                .contains("《国家基层高血压防治管理指南2025版》")
                .contains("v2025")
                .contains("（2025-01-01 生效）");

        JsonNode clauseResult = findResult(response, chunkId);
        assertThat(clauseResult.get("clauseId").asText()).isEqualTo("3.2");
        assertThat(clauseResult.get("clausePath").asText()).isEqualTo("第三章 > 3.2 诊断标准");
        assertThat(clauseResult.get("citation").asText())
                .isEqualTo("《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效）"
                        + " > 第三章 > 3.2 诊断标准 > 第12页");
    }

    @Test
    void tIntSearch02_keywordLiteralWildcardsAreEscaped() throws Exception {
        UUID docId = insertDocument("literal", "字面量测试", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        UUID literal = insertChunk(docId, "heading", 0, "合规率达到100%_已核验。", null, null, null);
        insertChunk(docId, "heading", 1, "合规率达到100AB已核验。", null, null, null);

        JsonNode response = search(Map.of(
                "query", "100%_",
                "strategy", "keyword",
                "asOf", "2026-11-09"), 200);

        assertThat(response.get("results")).hasSize(1);
        assertThat(response.get("results").get(0).get("chunkId").asText())
                .isEqualTo(literal.toString());
        assertThat(response.get("results").get(0).get("content").asText()).contains("100%_");
    }

    @Test
    void tIntSearch04_docIdsVersionAndChunkStrategyAreFiltered() throws Exception {
        UUID travel = insertDocument("travel", "差旅制度", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(travel, "heading", 0, "差旅报销制度规定十个工作日。", null, null, null);
        UUID leave = insertDocument("leave", "年假制度", "2024", "ACTIVE",
                LocalDate.of(2024, 1, 1), null);
        insertChunk(leave, "heading", 0, "年假制度规定十天。", null, null, null);

        JsonNode byDocId = search(Map.of(
                "query", "制度", "strategy", "keyword", "asOf", "2026-11-09",
                "docIds", List.of(travel.toString())), 200);
        assertThat(byDocId.get("results")).hasSize(1);
        assertThat(byDocId.get("results").get(0).get("docId").asText()).isEqualTo(travel.toString());

        JsonNode byVersion = search(Map.of(
                "query", "制度", "strategy", "keyword", "asOf", "2026-11-09",
                "version", "2024"), 200);
        assertThat(byVersion.get("results")).hasSize(1);
        assertThat(byVersion.get("results").get(0).get("docId").asText()).isEqualTo(leave.toString());

        JsonNode combined = search(Map.of(
                "query", "制度", "strategy", "keyword", "asOf", "2026-11-09",
                "docIds", List.of(travel.toString(), leave.toString()),
                "version", "2024"), 200);
        assertThat(combined.get("results")).hasSize(1);
        assertThat(combined.get("results").get(0).get("docId").asText()).isEqualTo(leave.toString());

        UUID both = insertDocument("both", "双策略", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        UUID fixedChunk = insertChunk(both, "fixed", 0, "策略命中的固定窗口文本。",
                null, null, null);
        UUID headingChunk = insertChunk(both, "heading", 0, "策略命中的标题感知文本。",
                null, null, null);
        JsonNode fixed = search(Map.of(
                "query", "策略命中", "strategy", "keyword", "asOf", "2026-11-09",
                "chunkStrategy", "fixed"), 200);
        assertThat(fixed.get("results")).hasSize(1);
        assertThat(fixed.get("results").get(0).get("chunkId").asText()).isEqualTo(fixedChunk.toString());
        JsonNode heading = search(Map.of(
                "query", "策略命中", "strategy", "keyword", "asOf", "2026-11-09",
                "chunkStrategy", "heading"), 200);
        assertThat(heading.get("results")).hasSize(1);
        assertThat(heading.get("results").get(0).get("chunkId").asText()).isEqualTo(headingChunk.toString());
    }

    @Test
    void tVersion02_03_06_11_versionModesAndRightClosedBoundary() throws Exception {
        UUID oldDoc = insertDocument("hypertension-guideline", "指南 2024", "2024",
                "SUPERSEDED", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        insertChunk(oldDoc, "heading", 0, "旧版高血压诊断标准为150/95。", "4.2",
                "第四章 > 4.2 诊断标准", 1);
        UUID newDoc = insertDocument("hypertension-guideline", "指南 2025", "2025",
                "ACTIVE", LocalDate.of(2025, 1, 1), null);
        insertChunk(newDoc, "heading", 0, "新版高血压诊断标准为140/90。", "4.2",
                "第四章 > 4.2 诊断标准", 4);
        UUID draftDoc = insertDocument("hypertension-guideline", "指南草稿", "2026",
                "DRAFT", LocalDate.of(2025, 1, 1), null);
        insertChunk(draftDoc, "heading", 0, "草稿高血压诊断标准不可召回。", "4.2",
                "第四章 > 4.2 诊断标准", 1);

        JsonNode current = search(Map.of(
                "query", "高血压", "strategy", "keyword", "asOf", "2026-11-09"), 200);
        assertThat(resultDocIds(current)).containsExactly(newDoc);

        JsonNode historical = search(Map.of(
                "query", "高血压", "strategy", "keyword", "asOf", "2024-06-01"), 200);
        assertThat(resultDocIds(historical)).containsExactly(oldDoc);
        assertThat(historical.get("results").get(0).get("docStatus").asText()).isEqualTo("SUPERSEDED");

        JsonNode include = search(Map.of(
                "query", "高血压", "strategy", "keyword", "asOf", "2026-11-09",
                "includeSuperseded", true), 200);
        assertThat(resultDocIds(include)).containsExactlyInAnyOrder(oldDoc, newDoc);
        assertThat(resultDocIds(include)).doesNotContain(draftDoc);

        JsonNode lastOldDay = search(Map.of(
                "query", "高血压", "strategy", "keyword", "asOf", "2024-12-31"), 200);
        assertThat(resultDocIds(lastOldDay)).containsExactly(oldDoc);

        JsonNode firstNewDay = search(Map.of(
                "query", "高血压", "strategy", "keyword", "asOf", "2025-01-01"), 200);
        assertThat(resultDocIds(firstNewDay)).containsExactly(newDoc);
    }

    @Test
    void tIntSearch05_noResultsReturns200WithEmptyArray() throws Exception {
        JsonNode emptyDatabase = search(Map.of(
                "query", "不存在", "topK", 5, "asOf", "2026-11-09"), 200);
        assertThat(emptyDatabase.get("results")).isEmpty();
        assertThat(emptyDatabase.get("conflicts")).isEmpty();

        UUID docId = insertDocument("none", "无命中", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        insertChunk(docId, "heading", 0, "完全无关的文本内容。", null, null, null);
        JsonNode keywordNoMatch = search(Map.of(
                "query", "zzz-not-in-kb", "strategy", "keyword", "asOf", "2026-11-09"), 200);
        assertThat(keywordNoMatch.get("results")).isEmpty();
    }

    @Test
    void contentOver2000CharsIsTruncatedWithEllipsis() throws Exception {
        UUID docId = insertDocument("long", "长文", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null);
        String content = "长文" + "x".repeat(2600);
        insertChunk(docId, "heading", 0, content, null, null, null);

        JsonNode response = search(Map.of(
                "query", "长文", "strategy", "keyword", "asOf", "2026-11-09"), 200);
        String returned = response.get("results").get(0).get("content").asText();
        assertThat(returned).hasSize(2001).endsWith("…");
    }

    @Test
    void parameterValidationUsesContractErrorCodes() throws Exception {
        searchExpectError(Map.of("query", " "), 400, "ERR-VALIDATION", "query");
        searchExpectError(Map.of("query", "q", "topK", 21), 400, "ERR-VALIDATION", "topK");
        searchExpectError(Map.of("query", "q", "docIds", List.of("not-a-uuid")),
                400, "ERR-VALIDATION", "docIds");
        searchExpectError(Map.of("query", "q", "chunkStrategy", "semantic"),
                400, "ERR-VALIDATION", "chunkStrategy");
        searchExpectError(Map.of("query", "q", "strategy", "bm25"),
                400, "ERR-VALIDATION", "strategy");
        searchExpectError(Map.of("query", "q", "asOf", "2024-6-1"),
                400, "ERR-INVALID_AS_OF", null);
        searchExpectError(Map.of("query", "q", "asOf", "1899-12-31"),
                400, "ERR-INVALID_AS_OF", null);
    }

    @Test
    void embeddingFailureReturns503AndKeywordOnlyStillWorks() throws Exception {
        FakeEmbeddingService.FAIL_NEXT.set(true);
        searchExpectError(Map.of("query", "q", "strategy", "vector", "asOf", "2026-11-09"),
                503, "ERR-EMBEDDING_UNAVAILABLE", null);

        FakeEmbeddingService.FAIL_NEXT.set(true);
        JsonNode keyword = search(Map.of(
                "query", "q", "strategy", "keyword", "asOf", "2026-11-09"), 200);
        assertThat(keyword.get("results")).isEmpty();
    }

    private JsonNode search(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void searchExpectError(Map<String, Object> body, int status, String code, String field)
            throws Exception {
        var request = post("/api/v1/search")
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

    private static JsonNode findResult(JsonNode response, UUID chunkId) {
        for (JsonNode result : response.get("results")) {
            if (chunkId.toString().equals(result.get("chunkId").asText())) {
                return result;
            }
        }
        throw new AssertionError("chunkId " + chunkId + " missing from search results");
    }

    private static List<UUID> resultDocIds(JsonNode response) {
        List<UUID> ids = new ArrayList<>();
        response.get("results").forEach(result -> ids.add(UUID.fromString(result.get("docId").asText())));
        return ids;
    }
}
