package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 version governance (T-VERSION-01/04/05/09/10/11): supersede, right-closed effective
 * intervals, overlap rejection, two chunk strategies, delete + ON DELETE SET NULL.
 */
class VersionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void tVersion04_tVersion11_newVersionSupersedesOldWithRightClosedBoundary() throws Exception {
        JsonNode oldDoc = upload(file("guide-2024.md", markdown("2024 版 高血压诊断标准为 140/90。")),
                metadata("hypertension", "2024", "2024-01-01", "fixed,heading"), 201);
        String oldId = oldDoc.get("docId").asText();
        int oldChunkCount = oldDoc.get("chunkCount").asInt();

        JsonNode newDoc = upload(file("guide-2025.md", markdown("2025 版 高血压诊断标准更新为 130/80。")),
                metadataWithSupersedes("hypertension", "2025", "2025-01-01", oldId), 201);
        String newId = newDoc.get("docId").asText();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM kb_document WHERE id = ?::uuid", String.class, oldId))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT effective_to FROM kb_document WHERE id = ?::uuid", LocalDate.class, oldId))
                .isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(jdbc.queryForObject(
                "SELECT supersedes_doc_id FROM kb_document WHERE id = ?::uuid",
                java.util.UUID.class, oldId)).isNull();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM kb_document WHERE id = ?::uuid", String.class, newId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT effective_from FROM kb_document WHERE id = ?::uuid", LocalDate.class, newId))
                .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(jdbc.queryForObject(
                "SELECT effective_to FROM kb_document WHERE id = ?::uuid", LocalDate.class, newId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT supersedes_doc_id FROM kb_document WHERE id = ?::uuid",
                java.util.UUID.class, newId)).isEqualTo(java.util.UUID.fromString(oldId));

        // T-VERSION-01: exactly one ACTIVE version per doc_key after supersede.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_document WHERE doc_key = 'hypertension' AND status = 'ACTIVE'",
                Integer.class)).isEqualTo(1);
        // T-VERSION-11: old version is valid on its last day, new version from its first day.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM kb_document
                WHERE id = ?::uuid AND effective_from <= DATE '2024-12-31'
                  AND (effective_to IS NULL OR DATE '2024-12-31' <= effective_to)
                """, Integer.class, oldId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM kb_document
                WHERE id = ?::uuid AND effective_from <= DATE '2025-01-01'
                  AND (effective_to IS NULL OR DATE '2025-01-01' <= effective_to)
                """, Integer.class, newId)).isEqualTo(1);

        // Old chunks are not deleted.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid", Integer.class, oldId))
                .isEqualTo(oldChunkCount);

        mockMvc.perform(get("/api/v1/documents/{docId}", oldId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.effectiveTo").value("2024-12-31"));
        mockMvc.perform(get("/api/v1/documents/{docId}", newId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.supersedesDocId").value(oldId));
    }

    @Test
    void tVersion05_overlappingActiveIntervalWithoutSupersedesReturns400() throws Exception {
        upload(file("old.txt", "旧版内容：生效区间从二零二四年开始，长期有效。".getBytes(StandardCharsets.UTF_8)),
                metadata("overlap", "2024", "2024-01-01", "heading"), 201);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/documents")
                        .file(file("new.txt", "新版内容不同：从二零二四年六月开始，但没有 supersedesDocId。"
                                .getBytes(StandardCharsets.UTF_8)))
                        .param("docKey", "overlap")
                        .param("version", "2025")
                        .param("effectiveFrom", "2024-06-01")
                        .param("chunkStrategies", "heading"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"))
                .andExpect(jsonPath("$.details.conflictDocId").exists());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isEqualTo(1);
    }

    @Test
    void nonOverlappingActiveIntervalsAreAllowed() throws Exception {
        Map<String, String> first = metadata("interval", "2024", "2024-01-01", "heading");
        first.put("effectiveTo", "2024-12-31");
        upload(file("a.txt", "第一段有效期内容 A。".getBytes(StandardCharsets.UTF_8)), first, 201);

        Map<String, String> params = metadata("interval", "2025", "2025-01-01", "heading");
        params.put("effectiveTo", "2025-12-31");
        JsonNode second = upload(file("b.txt", "第二段有效期内容 B。".getBytes(StandardCharsets.UTF_8)),
                params, 201);

        assertThat(second.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_document WHERE doc_key = 'interval' AND status = 'ACTIVE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void supersedesWithMismatchedDocKeyReturns400() throws Exception {
        JsonNode oldDoc = upload(file("a.md", markdown("A 文档第一版。")),
                metadata("doc-a", "2024", "2024-01-01", "heading"), 201);

        Map<String, String> params = metadata("doc-b", "2025", "2025-01-01", "heading");
        params.put("supersedesDocId", oldDoc.get("docId").asText());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/documents")
                        .file(file("b.md", markdown("B 文档第一版。")))
                        .param("docKey", params.get("docKey"))
                        .param("version", params.get("version"))
                        .param("effectiveFrom", params.get("effectiveFrom"))
                        .param("chunkStrategies", "heading")
                        .param("supersedesDocId", params.get("supersedesDocId")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM kb_document WHERE id = ?::uuid", String.class,
                oldDoc.get("docId").asText())).isEqualTo("ACTIVE");
    }

    @Test
    void supersedesWithSameEffectiveFromReturns400() throws Exception {
        JsonNode oldDoc = upload(file("a.md", markdown("旧版内容，生效日相同。")),
                metadata("same-day", "2024", "2025-01-01", "heading"), 201);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/documents")
                        .file(file("b.md", markdown("新版内容，生效日相同。")))
                        .param("docKey", "same-day")
                        .param("version", "2025")
                        .param("effectiveFrom", "2025-01-01")
                        .param("chunkStrategies", "heading")
                        .param("supersedesDocId", oldDoc.get("docId").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR-VALIDATION"));
    }

    @Test
    void deletingSupersededOldVersionSetsNewSupersedesToNull() throws Exception {
        JsonNode oldDoc = upload(file("old.md", markdown("旧版内容，将被删除。")),
                metadata("cascade", "2024", "2024-01-01", "fixed,heading"), 201);
        String oldId = oldDoc.get("docId").asText();
        JsonNode newDoc = upload(file("new.md", markdown("新版内容，保留。")),
                metadataWithSupersedes("cascade", "2025", "2025-01-01", oldId), 201);
        String newId = newDoc.get("docId").asText();

        mockMvc.perform(delete("/api/v1/documents/{docId}", oldId))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT supersedes_doc_id FROM kb_document WHERE id = ?::uuid",
                java.util.UUID.class, newId)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid", Integer.class, oldId)).isZero();
        mockMvc.perform(get("/api/v1/documents/{docId}", newId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supersedesDocId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void tVersion09_twoStrategiesCoexistWithIndependentIndexes() throws Exception {
        JsonNode doc = upload(file("both.md", markdown("包含标题的文本，用于验证双策略。")),
                metadata("both", "2026", "2026-01-01", "fixed,heading"), 201);
        String id = doc.get("docId").asText();

        for (String strategy : new String[]{"fixed", "heading"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT min(chunk_index) FROM kb_chunk WHERE document_id = ?::uuid AND chunk_strategy = ?",
                    Integer.class, id, strategy)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM kb_chunk WHERE document_id = ?::uuid AND chunk_strategy = ?",
                    Integer.class, id, strategy)).isGreaterThan(0);
        }
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT chunk_strategy FROM kb_chunk WHERE document_id = ?::uuid ORDER BY 1",
                String.class, id)).containsExactly("fixed", "heading");
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

    private Map<String, String> metadataWithSupersedes(String docKey, String version, String effectiveFrom,
                                                       String supersedesDocId) {
        Map<String, String> params = metadata(docKey, version, effectiveFrom, "fixed,heading");
        params.put("supersedesDocId", supersedesDocId);
        return params;
    }

    private byte[] markdown(String body) {
        String content = "# 高血压指南\n\n" + body + "\n\n## 1.1 诊断标准\n\n"
                + "本段用于提供可切分的中文正文，说明诊断阈值与测量要求。\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
