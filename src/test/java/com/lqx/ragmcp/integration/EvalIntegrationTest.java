package com.lqx.ragmcp.integration;

import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.eval.EvalDataset;
import com.lqx.ragmcp.eval.EvalQaItem;
import com.lqx.ragmcp.eval.EvalRunResult;
import com.lqx.ragmcp.eval.EvalService;
import com.lqx.ragmcp.eval.EvalSummary;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.FakeEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * T-INT-EVAL-01: the committed 40-question QA set runs end to end through the real retrieval
 * pipeline (Testcontainers pgvector + deterministic fake embedding) and produces bounded metrics
 * with errors counted separately.
 */
class EvalIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EvalService evalService;

    @Test
    void tIntEval01_fullQaSetProducesMetricsForBothStrategies() {
        EvalDataset dataset = EvalDataset.load(Path.of("eval/qa.jsonl"));
        assertThat(dataset.items()).hasSize(40);
        assertThat(dataset.answerableCount()).isEqualTo(35);
        assertThat(dataset.unanswerableCount()).isEqualTo(5);

        // One real hit for Q18: a chunk whose content is exactly the question, on gold page 2.
        EvalQaItem enterpriseGoal = dataset.items().stream()
                .filter(item -> "Q18".equals(item.id()))
                .findFirst()
                .orElseThrow();
        insertSyntheticDocumentAndChunk(enterpriseGoal.question());

        EvalRunResult heading = evalService.run(dataset, ChunkStrategy.HEADING,
                LocalDate.of(2026, 11, 9), 5);
        assertBounded(heading.summary());
        assertThat(heading.summary().answered()).isEqualTo(35);
        assertThat(heading.summary().errors()).isZero();
        assertThat(heading.questions()).hasSize(40);
        // Q18 should be found first (keyword route matches the exact question text).
        assertThat(heading.summary().hit5()).isGreaterThanOrEqualTo(1);
        assertThat(heading.summary().hitAt5()).isGreaterThanOrEqualTo(1.0 / 35.0 - 0.0001);
        assertThat(heading.summary().noAnswerAccuracy())
                .isCloseTo(1.0 - heading.summary().falsePositiveRate(), within(0.0001));

        EvalRunResult fixed = evalService.run(dataset, ChunkStrategy.FIXED,
                LocalDate.of(2026, 11, 9), 5);
        assertBounded(fixed.summary());
        assertThat(fixed.summary().answered()).isEqualTo(35);
        assertThat(fixed.summary().errors()).isZero();
        assertThat(fixed.summary().hitAt1()).isZero();
    }

    private static void assertBounded(EvalSummary summary) {
        assertThat(summary.hitAt1()).isBetween(0.0, 1.0);
        assertThat(summary.hitAt3()).isBetween(0.0, 1.0);
        assertThat(summary.hitAt5()).isBetween(0.0, 1.0);
        assertThat(summary.mrr()).isBetween(0.0, 1.0);
        assertThat(summary.falsePositiveRate()).isBetween(0.0, 1.0);
        assertThat(summary.noAnswerAccuracy()).isBetween(0.0, 1.0);
        assertThat(summary.answered() + summary.answerableErrors())
                .isEqualTo(summary.answerableCount());
        assertThat(summary.hitAt1()).isCloseTo(summary.hit1() / (double) summary.answered(),
                within(0.0001));
    }

    private void insertSyntheticDocumentAndChunk(String content) {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String checksum = String.format("%064x", UUID.randomUUID().hashCode() & 0xffffffffL);
        jdbc.update("""
                        INSERT INTO kb_document
                          (id, doc_key, title, source_type, source_uri, checksum, version,
                           embedding_model, effective_from, effective_to, status, metadata)
                        VALUES
                          (?::uuid, 'enterprise-internal-control', '企业内部控制基本规范', 'PDF',
                           'upload://eval-test.pdf', ?, '2026', 'bge-m3',
                           DATE '2026-01-01', NULL, 'ACTIVE', '{}'::jsonb)
                        """,
                docId.toString(), checksum);
        jdbc.update("""
                        INSERT INTO kb_chunk
                          (id, document_id, chunk_index, chunk_strategy, content, token_count,
                           clause_id, clause_path, page_no, metadata, embedding)
                        VALUES
                          (?::uuid, ?::uuid, 0, 'heading', ?, ?, NULL, NULL, 2, '{}'::jsonb, ?::vector)
                        """,
                chunkId.toString(), docId.toString(), content, Math.max(1, content.length() / 2),
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
