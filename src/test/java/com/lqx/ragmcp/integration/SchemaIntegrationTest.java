package com.lqx.ragmcp.integration;

import com.lqx.ragmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-INT-DB-01: Flyway V1 produces the six tables, extensions, indexes and constraints from
 * docs/03-data-model.md, plus the Phase 1 chunk_strategy contract.
 */
class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Test
    void tIntDb01_flywayV1CreatesTablesExtensionsAndIndexes() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).contains("kb_document", "kb_chunk", "work_item", "mandate",
                "mcp_audit_log", "eval_run");

        List<String> extensions = jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('vector','pg_trgm')", String.class);
        assertThat(extensions).containsExactlyInAnyOrder("vector", "pg_trgm");

        List<String> chunkColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'kb_chunk'",
                String.class);
        assertThat(chunkColumns).contains("chunk_strategy", "clause_id", "clause_path", "embedding",
                "token_count", "page_no", "page_end", "metadata", "chunk_index");
        assertThat(chunkColumns).doesNotContain("section_path");

        String strategyDefault = jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_name = 'kb_chunk' AND column_name = 'chunk_strategy'
                """, String.class);
        assertThat(strategyDefault).contains("heading");

        String strategyCheck = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_kb_chunk_strategy'",
                String.class);
        assertThat(strategyCheck).contains("'fixed'").contains("'heading'");

        List<String> indexDefs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename IN ('kb_document','kb_chunk')",
                String.class);
        assertThat(indexDefs).anySatisfy(def -> assertThat(def)
                .contains("hnsw").contains("vector_cosine_ops"));
        assertThat(indexDefs).anySatisfy(def -> assertThat(def).contains("gin_trgm_ops"));
        assertThat(indexDefs).anySatisfy(def -> assertThat(def)
                .contains("UNIQUE").contains("(checksum)"));
        assertThat(indexDefs).anySatisfy(def -> assertThat(def)
                .contains("(doc_key, status, effective_from, effective_to)"));

        String keyVersionConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'uk_kb_document_key_version'
                """, String.class);
        assertThat(keyVersionConstraint).contains("UNIQUE").contains("doc_key").contains("version")
                .contains("NULLS NOT DISTINCT");

        String chunkUnique = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'uk_kb_chunk_strategy'
                """, String.class);
        assertThat(chunkUnique).contains("document_id").contains("chunk_strategy")
                .contains("chunk_index");

        String deleteAction = jdbc.queryForObject("""
                SELECT confdeltype FROM pg_constraint
                WHERE conrelid = 'kb_document'::regclass AND confrelid = 'kb_document'::regclass
                """, String.class);
        assertThat(deleteAction).isEqualTo("n");

        String embeddingType = jdbc.queryForObject("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                WHERE attrelid = 'kb_chunk'::regclass AND attname = 'embedding'
                """, String.class);
        assertThat(embeddingType).isEqualTo("vector(1024)");
        String embeddingNullable = jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'kb_chunk' AND column_name = 'embedding'
                """, String.class);
        assertThat(embeddingNullable).isEqualTo("NO");

        String pageEndNullable = jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'kb_chunk' AND column_name = 'page_end'
                """, String.class);
        assertThat(pageEndNullable).isEqualTo("YES");

        List<String> evalColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'eval_run'",
                String.class);
        assertThat(evalColumns).contains("dataset_hash", "chunk_strategy", "embedding_model",
                "git_commit", "top_k", "qa_count", "hit_at_1", "hit_at_3", "hit_at_5", "mrr",
                "p50_ms", "p95_ms");

        String authorityCheck = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_kb_document_authority'
                """, String.class);
        assertThat(authorityCheck).contains("GROUP", "REGION", "SUBSIDIARY", "DEPARTMENT");

        String effectiveCheck = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_kb_document_effective'
                """, String.class);
        assertThat(effectiveCheck).contains("effective_from <= effective_to");
    }
}
