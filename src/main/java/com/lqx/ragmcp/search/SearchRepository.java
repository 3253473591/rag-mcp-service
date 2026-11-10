package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQL for the two retrieval routes. Every metadata filter (docIds, version, status,
 * effective interval, chunk strategy) is applied in SQL, never after fetching rows.
 *
 * <p><b>Vector route and HNSW.</b> A joined query
 * {@code kb_chunk JOIN kb_document ... ORDER BY embedding <=> ? LIMIT n} is planned by
 * PostgreSQL as a nested-loop over {@code kb_chunk_document_id} plus a top-N sort, not as an
 * HNSW index scan; at 10k+ chunks and 10 concurrent requests that saturated the DB and produced
 * multi-second p95 values. The vector route therefore first resolves the eligible document ids
 * with the same SQL filters, then asks HNSW for the top candidates with
 * {@code document_id IN (...) AND chunk_strategy = ?}. Filters still run in SQL before the
 * candidate limit; the document metadata is joined only for the selected candidates.</p>
 *
 * <p>HNSW filtering and pgvector &gt;= 0.8 under-return: when filters reduce the number of
 * matches, a plain HNSW scan may stop after ef_search candidates and return fewer than the
 * requested limit. The vector query therefore runs in a transaction with
 * {@code SET LOCAL hnsw.iterative_scan = relaxed_order}. The project pins pgvector 0.8.7;
 * see README "检索" and docs/acceptance/phase2-report.md.</p>
 */
@Repository
public class SearchRepository {

    private static final Logger log = LoggerFactory.getLogger(SearchRepository.class);

    private static final String CANDIDATE_COLUMNS = """
            c.id AS chunk_id,
            c.document_id AS document_id,
            c.chunk_index AS chunk_index,
            c.chunk_strategy AS chunk_strategy,
            c.content AS content,
            c.clause_id AS clause_id,
            c.clause_path AS clause_path,
            c.page_no AS page_no,
            c.page_end AS page_end,
            d.doc_key AS doc_key,
            d.title AS title,
            d.version AS doc_version,
            d.status AS doc_status,
            d.effective_from AS effective_from,
            d.effective_to AS effective_to
            """;

    private static final String CHUNK_COLUMNS = """
            c.id AS chunk_id,
            c.document_id AS document_id,
            c.chunk_index AS chunk_index,
            c.chunk_strategy AS chunk_strategy,
            c.content AS content,
            c.clause_id AS clause_id,
            c.clause_path AS clause_path,
            c.page_no AS page_no,
            c.page_end AS page_end
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final SearchProperties properties;
    private final RowMapper<SearchCandidate> candidateMapper = (rs, rowNum) -> mapCandidate(rs);

    public SearchRepository(JdbcTemplate jdbc,
                            TransactionTemplate transactionTemplate,
                            SearchProperties properties) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    public List<SearchCandidate> vectorSearch(SearchQuery query, float[] embedding, int limit) {
        String vectorLiteral = toVectorLiteral(embedding);
        List<SearchCandidate> rows = transactionTemplate.execute(status -> {
            List<UUID> docIds = eligibleDocumentIds(query);
            if (docIds.isEmpty()) {
                return List.<SearchCandidate>of();
            }
            if (properties.isHnswIterativeScan()) {
                // local=true = SET LOCAL; only affects this read transaction.
                jdbc.queryForObject(
                        "SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)", String.class);
            }
            Map<UUID, DocumentInfo> documents = loadDocuments(docIds);

            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT ")
                    .append(CHUNK_COLUMNS)
                    .append(", 1 - (c.embedding <=> ?::vector) AS route_score")
                    .append(" FROM kb_chunk c")
                    .append(" WHERE c.chunk_strategy = ?")
                    .append(" AND c.document_id IN (");
            args.add(vectorLiteral);
            args.add(query.chunkStrategy().dbValue());
            appendUuidPlaceholders(sql, args, docIds);
            sql.append(") ORDER BY c.embedding <=> ?::vector LIMIT ?");
            args.add(vectorLiteral);
            args.add(limit);

            return jdbc.query(sql.toString(), (rs, rowNum) -> {
                UUID documentId = rs.getObject("document_id", UUID.class);
                DocumentInfo document = documents.get(documentId);
                if (document == null) {
                    return null;
                }
                return new SearchCandidate(
                        rs.getObject("chunk_id", UUID.class),
                        documentId,
                        document.docKey(),
                        document.version(),
                        document.status(),
                        document.effectiveFrom(),
                        document.effectiveTo(),
                        document.title(),
                        rs.getString("clause_id"),
                        rs.getString("clause_path"),
                        rs.getObject("page_no", Integer.class),
                        rs.getObject("page_end", Integer.class),
                        rs.getString("content"),
                        rs.getString("chunk_strategy"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("route_score"));
            }, args.toArray()).stream().filter(candidate -> candidate != null).toList();
        });
        if (rows == null) {
            throw ApiException.dbUnavailable();
        }
        log.debug("vector search candidates={} limit={} mode={}", rows.size(), limit, query.versionMode());
        return rows;
    }

    /**
     * Lexical route over an OR of extracted terms.
     *
     * @param minTermHits how many distinct terms a chunk must contain. The hybrid route passes
     *                    {@code app.search.keyword-min-term-hits} (&gt;1 when several terms exist):
     *                    a single generic term ("标准", "要求") matches dozens of chunks and, fused at
     *                    equal weight, displaces correct vector hits. The explicit
     *                    {@code strategy=keyword} route passes 1 so a user asking for lexical search
     *                    gets lexical search. Clamped to the number of terms.
     */
    public List<SearchCandidate> keywordSearch(SearchQuery query, int limit, int minTermHits) {
        String keywordText = KeywordEscaper.keywordText(query.query(), properties.getKeywordMaxChars());
        List<String> terms = KeywordTerms.extract(keywordText, properties.getKeywordMaxTerms());
        if (terms.isEmpty()) {
            log.debug("keyword search skipped: no usable terms queryChars={}", keywordText.length());
            return List.of();
        }
        List<String> patterns = new ArrayList<>(terms.size());
        for (String term : terms) {
            patterns.add(KeywordEscaper.toPattern(term));
        }
        int requiredHits = Math.max(1, Math.min(minTermHits, terms.size()));

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT ")
                .append(CANDIDATE_COLUMNS)
                .append(", (");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) {
                sql.append(" + ");
            }
            // term_hits counts how many distinct query terms the chunk contains; the whole question
            // no longer has to appear verbatim (docs/11-retrieval-and-eval-spec.md 3.2).
            sql.append("CASE WHEN c.content ILIKE ? ESCAPE '\\' THEN 1 ELSE 0 END");
            args.add(patterns.get(i));
        }
        sql.append(") AS term_hits")
                .append(", similarity(c.content, ?) AS route_score")
                .append(" FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id")
                .append(" WHERE c.chunk_strategy = ?");
        args.add(keywordText);
        args.add(query.chunkStrategy().dbValue());
        appendFilters(sql, args, query);
        sql.append(" AND (");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("c.content ILIKE ? ESCAPE '\\'");
            args.add(patterns.get(i));
        }
        sql.append(")) t WHERE t.term_hits >= ?")
                .append(" ORDER BY t.term_hits DESC, t.route_score DESC, t.document_id,")
                .append(" t.chunk_strategy, t.chunk_index, t.chunk_id LIMIT ?");
        args.add(requiredHits);
        args.add(limit);

        List<SearchCandidate> rows = jdbc.query(sql.toString(), candidateMapper, args.toArray());
        log.debug("keyword search terms={} requiredHits={} candidates={} limit={} mode={}",
                terms.size(), requiredHits, rows.size(), limit, query.versionMode());
        return rows;
    }

    /**
     * Resolve document ids with the same filters used by the joined routes. Keeping this in SQL
     * preserves the contract that metadata filters are not applied in Java after retrieval.
     */
    private List<UUID> eligibleDocumentIds(SearchQuery query) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT d.id FROM kb_document d WHERE 1=1");
        appendFilters(sql, args, query);
        return jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
    }

    private Map<UUID, DocumentInfo> loadDocuments(List<UUID> docIds) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT d.id, d.doc_key, d.title, d.version, d.status, d.effective_from, d.effective_to")
                .append(" FROM kb_document d WHERE d.id IN (");
        appendUuidPlaceholders(sql, args, docIds);
        sql.append(')');
        Map<UUID, DocumentInfo> documents = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            documents.put(rs.getObject("id", UUID.class), new DocumentInfo(
                    rs.getString("doc_key"),
                    rs.getString("title"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class)));
        }, args.toArray());
        return documents;
    }

    private void appendFilters(StringBuilder sql, List<Object> args, SearchQuery query) {
        switch (query.versionMode()) {
            case DEFAULT -> sql.append(" AND d.status = 'ACTIVE'");
            case HISTORICAL, INCLUDE_SUPERSEDED ->
                    sql.append(" AND d.status IN ('ACTIVE','SUPERSEDED')");
        }

        if (query.versionMode() == SearchVersionMode.INCLUDE_SUPERSEDED) {
            sql.append(" AND d.effective_from <= ?::date");
            args.add(query.asOf().toString());
        } else {
            sql.append(" AND d.effective_from <= ?::date")
                    .append(" AND (d.effective_to IS NULL OR ?::date <= d.effective_to)");
            args.add(query.asOf().toString());
            args.add(query.asOf().toString());
        }

        if (query.version() != null) {
            sql.append(" AND d.version = ?");
            args.add(query.version());
        }

        if (!query.docIds().isEmpty()) {
            sql.append(" AND d.id IN (");
            appendUuidPlaceholders(sql, args, query.docIds());
            sql.append(')');
        }
    }

    private static void appendUuidPlaceholders(StringBuilder sql, List<Object> args, List<UUID> ids) {
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?::uuid");
            args.add(ids.get(i).toString());
        }
    }

    private static SearchCandidate mapCandidate(ResultSet rs) throws SQLException {
        return new SearchCandidate(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("doc_key"),
                rs.getString("doc_version"),
                rs.getString("doc_status"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getString("title"),
                rs.getString("clause_id"),
                rs.getString("clause_path"),
                rs.getObject("page_no", Integer.class),
                rs.getObject("page_end", Integer.class),
                rs.getString("content"),
                rs.getString("chunk_strategy"),
                rs.getInt("chunk_index"),
                rs.getDouble("route_score"));
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

    private record DocumentInfo(
            String docKey,
            String title,
            String version,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }
}
