package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentUploadIntegrationTest extends AbstractIntegrationTest {

    @Test
    void tIntDoc01_uploadMarkdownGeneratesFixedAndHeadingChunks() throws Exception {
        JsonNode response = upload(file("handbook.md", TestDocuments.markdown()),
                metadata("handbook", "2026", "2026-01-01", "fixed,heading"), 201);

        assertThat(response.get("duplicate").asBoolean()).isFalse();
        assertThat(response.get("sourceType").asText()).isEqualTo("MD");
        assertThat(response.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.get("title").asText()).isEqualTo("handbook");
        assertThat(strategies(response)).containsExactly("fixed", "heading");
        Map<String, Integer> counts = counts(response.get("chunkCounts"));
        assertThat(counts).containsKeys("fixed", "heading");
        assertThat(counts.get("fixed")).isGreaterThan(0);
        assertThat(counts.get("heading")).isGreaterThan(0);
        assertThat(response.get("chunkCount").asInt()).isEqualTo(counts.get("fixed") + counts.get("heading"));

        String docId = response.get("docId").asText();
        assertThat(dbChunkCounts(docId)).isEqualTo(counts);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid AND token_count <= 0",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy NOT IN ('fixed','heading')", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy = 'heading' AND clause_path IS NULL", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy = 'heading' AND clause_id = '1.1'", Integer.class, docId))
                .isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid AND embedding IS NULL",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT vector_dims(embedding) FROM kb_chunk WHERE document_id = ?::uuid LIMIT 1",
                Integer.class, docId)).isEqualTo(1024);

        MvcResult detailResult = mockMvc.perform(get("/api/v1/documents/{docId}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingModel").value("bge-m3"))
                .andExpect(jsonPath("$.chunkCount").value(counts.get("fixed") + counts.get("heading")))
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(detail.get("supersedesDocId").isNull()).isTrue();
        assertThat(counts(detail.get("chunkCounts"))).isEqualTo(counts);
        assertThat(strategies(detail)).containsExactly("fixed", "heading");

        mockMvc.perform(get("/api/v1/documents").param("limit", "20").param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items[0].docId").value(docId))
                .andExpect(jsonPath("$.items[0].chunkCount").value(counts.get("fixed") + counts.get("heading")));
    }

    @Test
    void tIntDoc01b_uploadPdfAndDocx() throws Exception {
        JsonNode pdf = upload(file("guideline.pdf", TestDocuments.pdfWithHeadings()),
                metadata("guideline", "2025", "2025-01-01", "fixed,heading"), 201);
        String pdfId = pdf.get("docId").asText();
        assertThat(pdf.get("sourceType").asText()).isEqualTo("PDF");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid AND page_no IS NOT NULL",
                Integer.class, pdfId)).isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy = 'heading' AND clause_id = '3.2'",
                Integer.class, pdfId)).isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy = 'fixed' AND clause_id IS NULL",
                Integer.class, pdfId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy = 'fixed' AND page_end IS NULL",
                Integer.class, pdfId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND page_end < page_no",
                Integer.class, pdfId)).isZero();

        JsonNode docx = upload(file("policy.docx", TestDocuments.docxWithHeadings()),
                metadata("policy", "2026", "2026-01-01", "fixed,heading"), 201);
        String docxId = docx.get("docId").asText();
        assertThat(docx.get("sourceType").asText()).isEqualTo("DOCX");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid "
                        + "AND chunk_strategy = 'heading' AND clause_path LIKE '%1.1 Definitions%'",
                Integer.class, docxId)).isGreaterThan(0);
    }

    @Test
    void tIntDoc02_duplicateUploadIsIdempotentAndDoesNotAddStrategies() throws Exception {
        byte[] content = TestDocuments.txt();
        JsonNode first = upload(file("policy.txt", content),
                metadata("policy", "2026", "2026-01-01", "heading"), 201);
        String docId = first.get("docId").asText();
        int chunkCount = first.get("chunkCount").asInt();
        assertThat(strategies(first)).containsExactly("heading");

        JsonNode second = upload(file("policy-renamed.txt", content),
                metadata("policy", "2026", "2026-01-01", "fixed,heading"), 200);

        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("docId").asText()).isEqualTo(docId);
        assertThat(second.get("chunkCount").asInt()).isEqualTo(chunkCount);
        assertThat(strategies(second)).containsExactly("heading");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid", Integer.class, docId))
                .isEqualTo(chunkCount);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid AND chunk_strategy = 'fixed'",
                Integer.class, docId)).isZero();
    }

    @Test
    void tIntDoc03_unsupportedExtensionReturns415() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file("malware.exe", "MZ".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("ERR-UNSUPPORTED_TYPE"))
                .andExpect(jsonPath("$.traceId").exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
    }

    @Test
    void tIntDoc04_emptyFileReturns422() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file("empty.txt", new byte[0])))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ERR-PARSE_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
    }

    @Test
    void scannedPdfReturns422AndWritesNothing() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file("scan.pdf", TestDocuments.blankPdf()))
                        .param("docKey", "scan")
                        .param("version", "2026"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ERR-PARSE_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk", Integer.class)).isZero();
    }

    @Test
    void embeddingFailureReturns503AndWritesNothing() throws Exception {
        FakeEmbeddingService.FAIL_NEXT.set(true);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file("handbook.md", TestDocuments.markdown()))
                        .param("chunkStrategies", "fixed,heading"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ERR-EMBEDDING_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk", Integer.class)).isZero();
    }

    @Test
    void tIntDoc05_deleteCascadesChunks() throws Exception {
        JsonNode response = upload(file("handbook.md", TestDocuments.markdown()),
                metadata("handbook", "2026", "2026-01-01", "fixed,heading"), 201);
        String docId = response.get("docId").asText();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid", Integer.class, docId))
                .isEqualTo(response.get("chunkCount").asInt());

        mockMvc.perform(delete("/api/v1/documents/{docId}", docId))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_chunk", Integer.class)).isZero();

        mockMvc.perform(delete("/api/v1/documents/{docId}", docId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR-DOC_NOT_FOUND"));
    }

    @Test
    void tIntDoc06_deleteUnknownReturns404() throws Exception {
        mockMvc.perform(delete("/api/v1/documents/{docId}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR-DOC_NOT_FOUND"));
    }

    @Test
    void sameDocKeyAndVersionWithDifferentContentReturns400() throws Exception {
        upload(file("a.txt", "第一版内容，用于测试同版本冲突。".getBytes(StandardCharsets.UTF_8)),
                metadata("policy", "2026", "2026-01-01", "heading"), 201);

        MvcResult result = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file("b.txt", "完全不同的内容，用于触发版本唯一约束。".getBytes(StandardCharsets.UTF_8)))
                        .param("docKey", "policy")
                        .param("version", "2026")
                        .param("effectiveFrom", "2026-01-01")
                        .param("chunkStrategies", "heading"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("SQL").doesNotContain("Exception");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents").param("docKey", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"));
    }

    @Test
    void listPaginationValidation() throws Exception {
        mockMvc.perform(get("/api/v1/documents").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"));
        mockMvc.perform(get("/api/v1/documents").param("limit", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/documents").param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/documents/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"));
    }

    private Map<String, String> metadata(String docKey, String version, String effectiveFrom,
                                         String chunkStrategies) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("docKey", docKey);
        params.put("version", version);
        params.put("effectiveFrom", effectiveFrom);
        params.put("chunkStrategies", chunkStrategies);
        return params;
    }

    private Map<String, Integer> counts(JsonNode node) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> counts.put(entry.getKey(), entry.getValue().asInt()));
        return counts;
    }

    private Map<String, Integer> dbChunkCounts(String docId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT chunk_strategy, count(*) AS c FROM kb_chunk WHERE document_id = ?::uuid "
                        + "GROUP BY chunk_strategy ORDER BY chunk_strategy", docId)) {
            counts.put((String) row.get("chunk_strategy"), ((Number) row.get("c")).intValue());
        }
        return counts;
    }

    private java.util.List<String> strategies(JsonNode node) {
        java.util.List<String> values = new java.util.ArrayList<>();
        node.get("chunkStrategies").forEach(value -> values.add(value.asText()));
        return values;
    }
}
