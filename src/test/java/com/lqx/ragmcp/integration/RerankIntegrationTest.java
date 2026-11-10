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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9: proves the rerank stage is wired in the real Spring context.
 *
 * <p>The two chunks are built so the two orderings disagree: the <em>keyword</em> chunk has both
 * route contributions and therefore the larger raw RRF score, while the <em>vector</em> chunk has
 * cosine 1.0 and no keyword hits. The rerank stage must put the vector chunk first even though its
 * exposed {@code score} is smaller; {@code rerankScore} explains why.</p>
 */
@TestPropertySource(properties = "app.search.rerank.enabled=true")
class RerankIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger CHECKSUM = new AtomicInteger();

    @Test
    void rerankOrdersByAbsoluteCosineAndKeepsRrfScoreMeaning() throws Exception {
        // No punctuation: QueryEmbeddingBatcher applies NFKC before embedding, which would turn a
        // full-width "？" into "?", so the test computes the same vector only for an unchanged text.
        String query = "预算控制的要求是什么";
        float[] queryVector = FakeEmbeddingService.vectorFor(query);

        UUID docId = insertDocument();
        UUID vectorFirst = insertChunk(docId, 0,
                "本段文本用于证明余弦相似度的绝对幅度可以压过更高的名次融合分，"
                        + "内容与问题词项无关，但向量与查询向量完全一致，长度为八十字符以上。",
                "第一条", "第一章 > 第一条", 1, queryVector);
        UUID keywordFirst = insertChunk(docId, 1,
                "预算控制要求：企业应当建立预算管理制度，明确预算编制、审批、执行和考核职责。",
                "第二条", "第一章 > 第二条", 2, orthogonal(queryVector));

        JsonNode response = search(Map.of(
                "query", query,
                "topK", 5,
                "asOf", "2026-11-09",
                "chunkStrategy", "heading",
                "strategy", "hybrid"), 200);

        JsonNode results = response.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("chunkId").asText()).isEqualTo(vectorFirst.toString());
        assertThat(results.get(1).get("chunkId").asText()).isEqualTo(keywordFirst.toString());

        // Contract: score keeps its Phase 8 meaning (raw RRF), so it is *not* monotone with the
        // reranked order here. rerankScore is the documented ordering key when rerank is enabled.
        double topScore = results.get(0).get("score").asDouble();
        double secondScore = results.get(1).get("score").asDouble();
        assertThat(topScore).isBetween(1.0d / 61.0d - 1e-12, 2.0d / 61.0d + 1e-12);
        assertThat(secondScore).isBetween(1.0d / 61.0d - 1e-12, 2.0d / 61.0d + 1e-12);
        assertThat(secondScore).isGreaterThan(topScore);

        double firstRerank = results.get(0).get("rerankScore").asDouble();
        double secondRerank = results.get(1).get("rerankScore").asDouble();
        assertThat(results.get(0).get("rerankScore").isNull()).isFalse();
        assertThat(firstRerank).isGreaterThan(secondRerank);
        assertThat(firstRerank).isGreaterThan(topScore);
    }

    @Test
    void rerankHandlesAnEmptyCandidateSet() throws Exception {
        JsonNode response = search(Map.of(
                "query", "完全不存在的主题",
                "topK", 5,
                "asOf", "2026-11-09"), 200);

        // No candidates: the rerank stage must be a no-op and the Phase 7 empty-result contract
        // (empty array + non-empty message + NO_MATCH) must stay intact.
        assertThat(response.get("results")).isEmpty();
        assertThat(response.get("message").asText()).isNotBlank();
        assertThat(response.get("abstained").asBoolean()).isTrue();
        assertThat(response.get("abstainReason").asText()).isEqualTo("NO_MATCH");
    }

    private JsonNode search(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private UUID insertDocument() {
        UUID id = UUID.randomUUID();
        String checksum = String.format("%064x", CHECKSUM.incrementAndGet());
        jdbc.update("""
                        INSERT INTO kb_document
                          (id, doc_key, title, source_type, source_uri, checksum, version,
                           embedding_model, effective_from, effective_to, status, metadata)
                        VALUES
                          (?::uuid, ?, ?, 'MD', ?, ?, '2025', 'bge-m3', ?::date, NULL, 'ACTIVE',
                           '{}'::jsonb)
                        """,
                id.toString(), "rerank-doc", "重排测试文档", "upload://rerank.md", checksum,
                LocalDate.of(2025, 1, 1).toString());
        return id;
    }

    private UUID insertChunk(UUID docId, int chunkIndex, String content, String clauseId,
                             String clausePath, int pageNo, float[] embedding) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO kb_chunk
                          (id, document_id, chunk_index, chunk_strategy, content, token_count,
                           clause_id, clause_path, page_no, metadata, embedding)
                        VALUES
                          (?::uuid, ?::uuid, ?, 'heading', ?, ?, ?, ?, ?, '{}'::jsonb, ?::vector)
                        """,
                id.toString(), docId.toString(), chunkIndex, content,
                Math.max(1, content.length() / 2), clauseId, clausePath, pageNo,
                vectorLiteral(embedding));
        return id;
    }

    /**
     * Rotates two components and re-normalizes: the result is a unit vector exactly orthogonal to
     * the query, so the chunk has cosine 0 regardless of the fake embedding's randomness.
     */
    private static float[] orthogonal(float[] vector) {
        float[] result = new float[vector.length];
        float a = vector[0];
        float b = vector[1];
        result[0] = -b;
        result[1] = a;
        double norm = Math.sqrt((double) a * a + (double) b * b);
        if (norm == 0.0d) {
            // Statistically impossible for the fake gaussian vector; keep the test deterministic.
            result[0] = 1.0f;
            norm = 1.0d;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) (result[i] / norm);
        }
        return result;
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
