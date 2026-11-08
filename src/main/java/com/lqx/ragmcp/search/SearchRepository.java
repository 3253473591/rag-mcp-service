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
import java.util.List;
import java.util.UUID;

/**
 * SQL for the two retrieval routes. Every metadata filter (docIds, version, status,
 * effective interval, chunk strategy) is applied in SQL, never after fetching rows.
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
            d.doc_key AS doc_key,
            d.title AS title,
            d.version AS doc_version,
            d.status AS doc_status,
            d.effective_from AS effective_from,
            d.effective_to AS effective_to
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
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(CANDIDATE_COLUMNS)
                .append(", 1 - (c.embedding <=> ?::vector) AS route_score")
                .append(" FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id")
                .append(" WHERE c.chunk_strategy = ?");
        args.add(vectorLiteral);
        args.add(query.chunkStrategy().dbValue());
        appendFilters(sql, args, query);
        sql.append(" ORDER BY c.embedding <=> ?::vector LIMIT ?");
        args.add(vectorLiteral);
        args.add(limit);

        List<SearchCandidate> rows = transactionTemplate.execute(status -> {
            if (properties.isHnswIterativeScan()) {
                // local=true = SET LOCAL; only affects this read transaction.
                jdbc.queryForObject(
                        "SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)", String.class);
            }
            return jdbc.query(sql.toString(), candidateMapper, args.toArray());
        });
        if (rows == null) {
            throw ApiException.dbUnavailable();
        }
        log.debug("vector search candidates={} limit={} mode={}", rows.size(), limit, query.versionMode());
        return rows;
    }

    public List<SearchCandidate> keywordSearch(SearchQuery query, int limit) {
        String keywordText = KeywordEscaper.keywordText(query.query(), properties.getKeywordMaxChars());
        String pattern = KeywordEscaper.toPattern(keywordText);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(CANDIDATE_COLUMNS)
                .append(", similarity(c.content, ?) AS route_score")
                .append(" FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id")
                .append(" WHERE c.chunk_strategy = ?");
        args.add(keywordText);
        args.add(query.chunkStrategy().dbValue());
        appendFilters(sql, args, query);
        sql.append(" AND c.content ILIKE ? ESCAPE '\\'")
                .append(" ORDER BY route_score DESC, c.document_id, c.chunk_strategy,")
                .append(" c.chunk_index, c.id LIMIT ?");
        args.add(pattern);
        args.add(limit);

        List<SearchCandidate> rows = jdbc.query(sql.toString(), candidateMapper, args.toArray());
        log.debug("keyword search candidates={} limit={} mode={}", rows.size(), limit, query.versionMode());
        return rows;
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
            sql.append(" AND c.document_id IN (");
            for (int i = 0; i < query.docIds().size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?::uuid");
                args.add(query.docIds().get(i).toString());
            }
            sql.append(')');
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
}
