package com.lqx.ragmcp.page;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.SearchProperties;
import com.lqx.ragmcp.search.DocumentFilterSql;
import com.lqx.ragmcp.search.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQL for {@code kb_page}.
 *
 * <p><b>Filters are in SQL.</b> The page vector route resolves the eligible document ids with the
 * same {@link DocumentFilterSql} predicates as {@code SearchRepository}, then asks HNSW for the
 * top pages restricted to those ids. Nothing is fetched and filtered afterwards in Java. This
 * mirrors the existing chunk vector route (including {@code hnsw.iterative_scan} under pgvector
 * &gt;= 0.8) instead of inventing a second filtering strategy.</p>
 */
@Repository
public class PageRepository {

    private static final Logger log = LoggerFactory.getLogger(PageRepository.class);

    private static final String PAGE_COLUMNS = """
            p.id AS page_id,
            p.document_id AS document_id,
            p.page_key AS page_key,
            p.clause_path AS clause_path,
            p.leaf_clause_id AS leaf_clause_id,
            p.clause_ids AS clause_ids,
            p.title AS title,
            p.page_index AS page_index,
            p.part AS part,
            p.body AS body,
            p.token_count AS token_count,
            p.page_no AS page_no,
            p.page_end AS page_end,
            p.source_chunk_ids AS source_chunk_ids
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final SearchProperties searchProperties;

    public PageRepository(JdbcTemplate jdbc,
                          TransactionTemplate transactionTemplate,
                          SearchProperties searchProperties) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.searchProperties = searchProperties;
    }

    /** Heading chunks of one document in chunk_index order, ready for aggregation. */
    public List<PageSourceChunk> findHeadingChunks(UUID documentId) {
        return jdbc.query("""
                        SELECT id, chunk_index, content, token_count, clause_id, clause_path,
                               page_no, page_end
                        FROM kb_chunk
                        WHERE document_id = ?::uuid AND chunk_strategy = 'heading'
                        ORDER BY chunk_index
                        """,
                (rs, rowNum) -> new PageSourceChunk(
                        rs.getObject("id", UUID.class),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getString("clause_id"),
                        rs.getString("clause_path"),
                        rs.getObject("page_no", Integer.class),
                        rs.getObject("page_end", Integer.class)),
                documentId.toString());
    }

    public List<UUID> allDocumentIds() {
        return jdbc.queryForList("SELECT id FROM kb_document ORDER BY created_at, id", UUID.class);
    }

    public int deleteByDocumentId(UUID documentId) {
        return jdbc.update("DELETE FROM kb_page WHERE document_id = ?::uuid", documentId.toString());
    }

    public void insertPages(List<PageInsert> pages) {
        if (pages.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO kb_page
                  (id, document_id, page_key, clause_path, leaf_clause_id, clause_ids, title,
                   page_index, part, body, token_count, page_no, page_end, source_chunk_ids,
                   embedding)
                VALUES
                  (?::uuid, ?::uuid, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?::vector)
                """;
        List<Object[]> batch = new ArrayList<>(pages.size());
        for (PageInsert page : pages) {
            PageDraft draft = page.draft();
            batch.add(new Object[]{
                    page.id().toString(),
                    page.documentId().toString(),
                    draft.pageKey(),
                    draft.clausePath(),
                    draft.leafClauseId(),
                    textArrayLiteral(draft.clauseIds()),
                    draft.title(),
                    draft.pageIndex(),
                    draft.part(),
                    draft.body(),
                    draft.tokenCount(),
                    draft.pageNo(),
                    draft.pageEnd(),
                    uuidArrayLiteral(draft.sourceChunkIds()),
                    toVectorLiteral(page.embedding())
            });
        }
        jdbc.batchUpdate(sql, batch);
    }

    public int countByDocumentId(UUID documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_page WHERE document_id = ?::uuid", Integer.class,
                documentId.toString());
        return count == null ? 0 : count;
    }

    /**
     * Vector route over pages. Filters (status/effective interval/version/docIds) select the
     * eligible documents in SQL before the HNSW candidate limit is applied.
     */
    public List<PageCandidate> vectorSearch(SearchQuery query, float[] embedding, int limit) {
        String vectorLiteral = toVectorLiteral(embedding);
        List<PageCandidate> rows = transactionTemplate.execute(status -> {
            List<UUID> docIds = eligibleDocumentIds(query);
            if (docIds.isEmpty()) {
                return List.<PageCandidate>of();
            }
            if (searchProperties.isHnswIterativeScan()) {
                jdbc.queryForObject(
                        "SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)", String.class);
            }
            Map<UUID, DocInfo> documents = loadDocuments(docIds);

            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT ")
                    .append(PAGE_COLUMNS)
                    .append(", 1 - (p.embedding <=> ?::vector) AS route_score")
                    .append(" FROM kb_page p WHERE p.document_id IN (");
            args.add(vectorLiteral);
            DocumentFilterSql.appendUuidPlaceholders(sql, args, docIds);
            sql.append(") ORDER BY p.embedding <=> ?::vector, p.document_id, p.page_index, p.part LIMIT ?");
            args.add(vectorLiteral);
            args.add(limit);

            return jdbc.query(sql.toString(), (rs, rowNum) -> mapCandidate(rs, documents),
                    args.toArray()).stream().filter(candidate -> candidate != null).toList();
        });
        if (rows == null) {
            throw ApiException.dbUnavailable();
        }
        log.debug("page vector search candidates={} limit={} mode={}",
                rows.size(), limit, query.versionMode());
        return rows;
    }

    /**
     * Per-document page statistics for the acceptance report. {@code pageCount} counts rows
     * (parts included); {@code pageGroups} counts distinct page keys before oversize splitting.
     */
    public List<DocumentPageStats> stats() {
        return jdbc.query("""
                        SELECT d.id, d.doc_key, d.title, d.version, d.status,
                               count(p.id) AS page_count,
                               count(DISTINCT p.page_key) AS page_groups,
                               COALESCE(sum(p.token_count), 0) AS total_tokens,
                               COALESCE(avg(p.token_count), 0) AS avg_tokens,
                               COALESCE(max(p.token_count), 0) AS max_tokens
                        FROM kb_document d
                        LEFT JOIN kb_page p ON p.document_id = d.id
                        GROUP BY d.id, d.doc_key, d.title, d.version, d.status, d.created_at
                        ORDER BY d.created_at, d.id
                        """,
                (rs, rowNum) -> new DocumentPageStats(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_key"),
                        rs.getString("title"),
                        rs.getString("version"),
                        rs.getString("status"),
                        rs.getInt("page_count"),
                        rs.getInt("page_groups"),
                        rs.getLong("total_tokens"),
                        Math.round(rs.getDouble("avg_tokens") * 10.0d) / 10.0d,
                        rs.getInt("max_tokens")));
    }

    /** Same predicates as the chunk vector route, resolved in SQL. */
    private List<UUID> eligibleDocumentIds(SearchQuery query) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT d.id FROM kb_document d WHERE 1=1");
        DocumentFilterSql.appendFilters(sql, args, query);
        return jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
    }

    private Map<UUID, DocInfo> loadDocuments(List<UUID> docIds) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT d.id, d.doc_key, d.title, d.version, d.status, d.effective_from, d.effective_to")
                .append(" FROM kb_document d WHERE d.id IN (");
        DocumentFilterSql.appendUuidPlaceholders(sql, args, docIds);
        sql.append(')');
        Map<UUID, DocInfo> documents = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            documents.put(rs.getObject("id", UUID.class), new DocInfo(
                    rs.getString("doc_key"),
                    rs.getString("title"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class)));
        }, args.toArray());
        return documents;
    }

    private static PageCandidate mapCandidate(ResultSet rs, Map<UUID, DocInfo> documents)
            throws SQLException {
        UUID documentId = rs.getObject("document_id", UUID.class);
        DocInfo document = documents.get(documentId);
        if (document == null) {
            return null;
        }
        return new PageCandidate(
                rs.getObject("page_id", UUID.class),
                documentId,
                document.docKey(),
                document.version(),
                document.status(),
                document.effectiveFrom(),
                document.effectiveTo(),
                document.title(),
                rs.getString("page_key"),
                rs.getString("clause_path"),
                rs.getString("leaf_clause_id"),
                readStringArray(rs, "clause_ids"),
                rs.getString("title"),
                rs.getInt("page_index"),
                rs.getInt("part"),
                rs.getString("body"),
                rs.getInt("token_count"),
                rs.getObject("page_no", Integer.class),
                rs.getObject("page_end", Integer.class),
                readUuidArray(rs, "source_chunk_ids"),
                rs.getDouble("route_score"));
    }

    private static List<String> readStringArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return values == null ? List.of() : List.of(values);
    }

    private static List<UUID> readUuidArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        UUID[] values = (UUID[]) array.getArray();
        return values == null ? List.of() : List.of(values);
    }

    private static String textArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeArrayElement(values.get(i))).append('"');
        }
        return builder.append('}').toString();
    }

    private static String uuidArrayLiteral(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        return builder.append('}').toString();
    }

    private static String escapeArrayElement(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10 + 2);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(vector[i]));
        }
        builder.append(']');
        return builder.toString();
    }

    public record PageInsert(UUID id, UUID documentId, PageDraft draft, float[] embedding) {
    }

    public record DocumentPageStats(
            UUID documentId,
            String docKey,
            String docTitle,
            String version,
            String status,
            int pageCount,
            int pageGroups,
            long totalTokens,
            double avgTokens,
            int maxTokens) {
    }

    private record DocInfo(
            String docKey,
            String title,
            String version,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }
}
