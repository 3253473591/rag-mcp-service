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
 * Phase 8: proves the abstention guard is wired in the real Spring context, not only in unit tests.
 *
 * <p>The previous setup switched the guard off for every integration test
 * ({@code app.search.abstain.enabled=false}), so nothing below {@code SearchService} was ever
 * exercised with it on. The global test properties now make the guard <em>inert</em> (thresholds no
 * score can trip) and this class raises the floor above 1.0, so {@code NO_MATCH} is deterministic
 * regardless of the fake embedding.</p>
 */
@TestPropertySource(properties = {
        "app.search.abstain.min-top-score=2.0",
        "app.search.abstain.high-confidence-top-score=2.0"
})
class AbstainGuardIntegrationTest extends AbstractIntegrationTest {

    @Test
    void lowEvidenceQueryReturnsHonestNoMatchWithDiagnostics() throws Exception {
        insertDocumentWithChunk("abstain-guard", "合规率达到100%_已核验。");

        JsonNode response = search(Map.of("query", "公司年假有多少天？", "asOf", "2026-11-09"));

        assertThat(response.get("abstained").asBoolean()).isTrue();
        assertThat(response.get("abstainReason").asText()).isEqualTo("NO_MATCH");
        assertThat(response.get("results")).isEmpty();
        assertThat(response.get("message").asText()).isNotBlank();
        assertThat(response.get("message").asText()).contains("未检索到");

        JsonNode diagnostics = response.get("diagnostics");
        assertThat(diagnostics.get("reason").asText()).isEqualTo("NO_MATCH");
        assertThat(diagnostics.get("enabled").asBoolean()).isTrue();
        assertThat(diagnostics.get("minTopScore").asDouble()).isEqualTo(2.0d);
        assertThat(diagnostics.get("topScore").asDouble()).isLessThan(2.0d);
        assertThat(diagnostics.get("candidateCount").asInt()).isGreaterThan(0);
    }

    @Test
    void keywordOnlySearchStaysNeutralWhenTheVectorRouteHasNoCosine() throws Exception {
        insertDocumentWithChunk("abstain-keyword", "差旅报销制度规定十个工作日。");

        JsonNode response = search(Map.of(
                "query", "差旅报销的时限是多少？",
                "strategy", "keyword",
                "asOf", "2026-11-09"));

        assertThat(response.get("abstained").asBoolean()).isFalse();
        assertThat(response.get("results")).isNotEmpty();
        assertThat(response.get("message").isNull()).isTrue();
        assertThat(response.get("diagnostics").get("candidateCount").asInt()).isZero();
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
