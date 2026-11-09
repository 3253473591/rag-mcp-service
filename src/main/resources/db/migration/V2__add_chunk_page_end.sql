-- Phase 6: page span for fixed-window chunks.
-- page_no remains the 1-based start page (existing contract); page_end is the inclusive last
-- page covered by the chunk. Heading-aware chunks usually have page_end = page_no unless a
-- section continues across a page boundary. NULL means the parser had no page information.
ALTER TABLE kb_chunk ADD COLUMN page_end INT;

ALTER TABLE kb_chunk
  ADD CONSTRAINT ck_kb_chunk_page_span
  CHECK (page_no IS NULL OR page_end IS NULL OR page_end >= page_no);
