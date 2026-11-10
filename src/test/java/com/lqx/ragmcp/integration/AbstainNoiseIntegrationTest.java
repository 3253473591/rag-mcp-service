package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8: the {@code TOO_NOISY} branch of the abstention guard, exercised through the real Spring
 * wiring. Properties are shaped so the branch is deterministic: the floor is below any cosine
 * ({@code -1.0}), "high confidence" is unreachable ({@code 2.0}), every candidate counts as dense
 * ({@code 0}), and the gap requirement can never be met ({@code 2.0}, while cosine distance is at
 * most 2 in theory but a top-1 vs top-2 gap of the fake vectors is far below it).
 */
@TestPropertySource(properties = {
        "app.search.abstain.min-top-score=-1.0",
        "app.search.abstain.high-confidence-top-score=2.0",
        "app.search.abstain.min-dense-candidates=0",
        "app.search.abstain.min-score-gap=2.0"
})
class AbstainNoiseIntegrationTest extends AbstractIntegrationTest {

    @Test
    void flatScoreProfileIsReportedAsTooNoisyNotAsAnEmptyReply() throws Exception {
        insertDocumentWithChunk("abstain-noise", "内部控制制度规定授权审批应当分级管理。");

        JsonNode response = search(Map.of("query", "审批权限如何划分？", "asOf", "2026-11-09"));

        assertThat(response.get("abstained").asBoolean()).isTrue();
        assertThat(response.get("abstainReason").asText()).isEqualTo("TOO_NOISY");
        assertThat(response.get("results")).isEmpty();
        assertThat(response.get("message").asText()).isNotBlank();
        assertThat(response.get("message").asText()).contains("噪声");

        JsonNode diagnostics = response.get("diagnostics");
        assertThat(diagnostics.get("reason").asText()).isEqualTo("TOO_NOISY");
        assertThat(diagnostics.get("highConfidenceTopScore").asDouble()).isEqualTo(2.0d);
        assertThat(diagnostics.get("candidateCount").asInt()).isGreaterThan(0);
        assertThat(diagnostics.get("denseCount").asInt()).isEqualTo(diagnostics.get("candidateCount").asInt());
    }

    private JsonNode search(Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void insertDocumentWithChunk(String docKey, String content) {
        UUID docId = UUID.randomUUID();
        String checksum = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                        INSERT INTO kb_document
                          (id, doc_key, title, source_type, source_uri, checksum, version,
                           embedding_model, effective_from, effective_to, status, metadata)
                        VALUES
                          (?::uuid, ?, ?, 'MD', ?, ?, '2025', 'bge-m3', ?::date, NULL, 'ACTIVE',
                           '{}'::jsonb)
                        """,
                docId.toString(), docKey, docKey, "upload://test.md", checksum,
                LocalDate.of(2025, 1, 1).toString());
        jdbc.update("""
                        INSERT INTO kb_chunk
                          (id, document_id, chunk_index, chunk_strategy, content, token_count,
                           clause_id, clause_path, page_no, metadata, embedding)
                        VALUES
                          (?::uuid, ?::uuid, 0, 'heading', ?, ?, NULL, NULL, 1, '{}'::jsonb, ?::vector)
                        """,
                UUID.randomUUID().toString(), docId.toString(), content,
                Math.max(1, content.length() / 2),
                vectorLiteral(FakeEmbeddingService.vectorFor(content)));
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
