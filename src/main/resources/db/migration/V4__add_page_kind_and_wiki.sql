-- Phase 11: LLM-compiled wiki pages coexist with deterministic aggregate pages.
--
-- kb_page already holds one row per deterministic leaf-section aggregation (kind='aggregate',
-- Phase 10). This migration adds a second, independent flavour of the same page layer:
--
--   kind       'aggregate' (PageAggregator, no LLM) or 'wiki' (WikiCompiler, DeepSeek)
--   llm_model  model id used to compile a wiki page; NULL for aggregate pages
--
-- The uniqueness rule must allow BOTH a 'aggregate' and a 'wiki' page for the same document
-- section, so (document_id, page_key, part) is extended with kind. Reb building one kind
-- deletes only that kind, which keeps the other flavour intact and the operation idempotent.
--
-- No LLM output is stored in this migration; existing rows are aggregate pages, hence the
-- DEFAULT 'aggregate' backfill.

ALTER TABLE kb_page
  ADD COLUMN kind TEXT NOT NULL DEFAULT 'aggregate';

ALTER TABLE kb_page
  ADD COLUMN llm_model TEXT;

ALTER TABLE kb_page
  DROP CONSTRAINT uk_kb_page_document_key;

ALTER TABLE kb_page
  ADD CONSTRAINT uk_kb_page_document_key UNIQUE (document_id, page_key, part, kind);

ALTER TABLE kb_page
  ADD CONSTRAINT ck_kb_page_kind CHECK (kind IN ('aggregate', 'wiki'));

CREATE INDEX idx_kb_page_kind ON kb_page(kind);
