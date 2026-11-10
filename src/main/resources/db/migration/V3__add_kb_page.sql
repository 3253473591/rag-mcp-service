-- Phase 10: page layer.
--
-- kb_page is a deterministic aggregation of heading-strategy chunks into leaf sections
-- (clause_path). It is a second retrieval granularity, not a replacement for kb_chunk: the
-- existing POST /api/v1/search contract and ordering are untouched.
--
-- Notes:
--   * There is no LLM here. Every column is derived from kb_chunk by PageAggregator:
--     grouping key = clause_path (NULL/blank -> '__root__'), body = chunk contents joined in
--     chunk_index order, split at chunk boundaries when the body exceeds app.pages.max-tokens.
--   * page_key is the stable identifier (doc_key/version + clause_path) and part is the
--     1-based ordinal after oversize splitting, so UNIQUE (document_id, page_key, part)
--     leaves one row per page part (and makes re-ingestion idempotent after a delete).
--   * leaf_clause_id is the clause id of the page's own leaf heading. It is what the strict
--     page-side eval口径 compares against gold.clauseId; clause_ids holds every distinct
--     chunk clause id in the group (the loose口径 uses it).
--   * source_chunk_ids answers "which chunks produced this page"; clause_ids answers "which
--     clauses does it cover"; page_no/page_end answer "which pages".
--
-- The migration is V3 because V2 is already taken by the Phase 6 chunk page_end migration.

CREATE TABLE kb_page (
  id                UUID PRIMARY KEY,
  document_id       UUID NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
  page_key          TEXT NOT NULL,
  clause_path       TEXT,
  leaf_clause_id    TEXT,
  clause_ids        TEXT[] NOT NULL DEFAULT '{}',
  title             TEXT NOT NULL,
  page_index        INT NOT NULL,
  part              INT NOT NULL DEFAULT 1,
  body              TEXT NOT NULL,
  token_count       INT NOT NULL,
  page_no           INT,
  page_end          INT,
  source_chunk_ids  UUID[] NOT NULL DEFAULT '{}',
  embedding         VECTOR(1024) NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_kb_page_document_key UNIQUE (document_id, page_key, part),
  CONSTRAINT ck_kb_page_part CHECK (part >= 1),
  CONSTRAINT ck_kb_page_page_span CHECK (page_no IS NULL OR page_end IS NULL OR page_end >= page_no)
);

CREATE INDEX idx_kb_page_document_id ON kb_page(document_id);

CREATE INDEX idx_kb_page_embedding
  ON kb_page USING hnsw (embedding vector_cosine_ops);
