-- Phase 1 schema: kb_document / kb_chunk / work_item / mandate / mcp_audit_log / eval_run
-- Contract: docs/03-data-model.md (DDL). Single source of truth for all DDL: Flyway only.
-- Notes:
--   * kb_document (doc_key, version) is UNIQUE NULLS NOT DISTINCT so a NULL version
--     cannot silently bypass the one-version-per-doc_key rule (PostgreSQL 15+).
--   * kb_chunk.chunk_strategy is lowercase ('fixed' / 'heading'); two strategies coexist
--     per document and chunk_index is unique only inside one strategy.
--   * Clause metadata uses clause_id / clause_path only. There is no section_path.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE kb_document (
  id                UUID PRIMARY KEY,
  doc_key           TEXT NOT NULL,
  title             TEXT NOT NULL,
  source_type       TEXT NOT NULL,
  source_uri        TEXT,
  checksum          CHAR(64) NOT NULL,
  version           TEXT,
  embedding_model   TEXT NOT NULL DEFAULT 'bge-m3',
  effective_from    DATE NOT NULL DEFAULT CURRENT_DATE,
  effective_to      DATE,
  status            TEXT NOT NULL DEFAULT 'ACTIVE',
  supersedes_doc_id UUID REFERENCES kb_document(id) ON DELETE SET NULL,
  authority_level   TEXT,
  scope             TEXT,
  topic_key         TEXT,
  metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ,
  CONSTRAINT ck_kb_document_status
    CHECK (status IN ('DRAFT','ACTIVE','SUPERSEDED')),
  CONSTRAINT ck_kb_document_authority
    CHECK (authority_level IS NULL OR authority_level IN ('GROUP','REGION','SUBSIDIARY','DEPARTMENT')),
  CONSTRAINT ck_kb_document_effective
    CHECK (effective_to IS NULL OR effective_from <= effective_to),
  CONSTRAINT uk_kb_document_key_version UNIQUE NULLS NOT DISTINCT (doc_key, version)
);

CREATE UNIQUE INDEX uk_kb_document_checksum
  ON kb_document(checksum);

CREATE INDEX idx_kb_document_doc_key
  ON kb_document(doc_key, status, effective_from, effective_to);

CREATE INDEX idx_kb_document_created_at
  ON kb_document(created_at DESC);

CREATE TABLE kb_chunk (
  id             UUID PRIMARY KEY,
  document_id    UUID NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
  chunk_index    INT NOT NULL,
  chunk_strategy TEXT NOT NULL DEFAULT 'heading',
  content        TEXT NOT NULL,
  token_count    INT NOT NULL,
  clause_id      TEXT,
  clause_path    TEXT,
  page_no        INT,
  metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
  embedding      VECTOR(1024) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_kb_chunk_strategy CHECK (chunk_strategy IN ('fixed','heading')),
  CONSTRAINT uk_kb_chunk_strategy UNIQUE (document_id, chunk_strategy, chunk_index)
);

CREATE INDEX idx_kb_chunk_document_id
  ON kb_chunk(document_id);

CREATE INDEX idx_kb_chunk_embedding
  ON kb_chunk USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_kb_chunk_content_trgm
  ON kb_chunk USING gin (content gin_trgm_ops);

CREATE TABLE work_item (
  id                UUID PRIMARY KEY,
  requester         TEXT NOT NULL,
  purpose           TEXT NOT NULL,
  requested_tools   JSONB NOT NULL,
  requested_doc_ids JSONB,
  requested_top_k   INT NOT NULL DEFAULT 5,
  status            TEXT NOT NULL DEFAULT 'PENDING',
  decided_by        TEXT,
  decided_at        TIMESTAMPTZ,
  decision_reason   TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_work_item_status CHECK (status IN ('PENDING','APPROVED','DENIED')),
  CONSTRAINT ck_work_item_top_k CHECK (requested_top_k BETWEEN 1 AND 20)
);

CREATE INDEX idx_work_item_status_created
  ON work_item(status, created_at DESC);

CREATE TABLE mandate (
  id                UUID PRIMARY KEY,
  work_item_id      UUID NOT NULL REFERENCES work_item(id) ON DELETE CASCADE,
  requester         TEXT NOT NULL,
  allowed_tools     JSONB NOT NULL,
  allowed_doc_ids   JSONB,
  max_top_k         INT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'ACTIVE',
  expires_at        TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at        TIMESTAMPTZ,
  CONSTRAINT ck_mandate_status CHECK (status IN ('ACTIVE','REVOKED')),
  CONSTRAINT ck_mandate_top_k CHECK (max_top_k BETWEEN 1 AND 20),
  CONSTRAINT uk_mandate_work_item UNIQUE (work_item_id)
);

CREATE INDEX idx_mandate_status_expires
  ON mandate(status, expires_at);

CREATE TABLE mcp_audit_log (
  id                  BIGSERIAL PRIMARY KEY,
  tool_name           TEXT NOT NULL,
  work_item_id        UUID,
  mandate_id          UUID,
  caller_fingerprint  CHAR(12),
  args_hash           CHAR(64),
  status              TEXT NOT NULL,
  error_code          TEXT,
  latency_ms          INT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mcp_audit_created_at
  ON mcp_audit_log(created_at DESC);

CREATE TABLE eval_run (
  id             BIGSERIAL PRIMARY KEY,
  dataset_hash   TEXT,
  chunk_strategy TEXT NOT NULL,
  embedding_model TEXT,
  git_commit     TEXT,
  top_k          INT,
  qa_count       INT NOT NULL,
  hit_at_1       NUMERIC(5,4),
  hit_at_3       NUMERIC(5,4),
  hit_at_5       NUMERIC(5,4),
  mrr            NUMERIC(6,4),
  p50_ms         INT,
  p95_ms         INT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_eval_run_strategy CHECK (chunk_strategy IN ('fixed','heading'))
);
