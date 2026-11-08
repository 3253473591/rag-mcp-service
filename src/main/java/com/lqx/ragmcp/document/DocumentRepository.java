package com.lqx.ragmcp.document;

import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.chunk.PreparedChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRepository {

    private static final String DOC_COLUMNS = """
            id, doc_key, title, source_type, source_uri, checksum, version, embedding_model,
            effective_from, effective_to, status, supersedes_doc_id, authority_level, scope,
            topic_key, metadata, created_at, updated_at
            """;

    private static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

    private final JdbcTemplate jdbc;
    private final RowMapper<DocumentRecord> documentMapper = (rs, rowNum) -> mapDocument(rs);

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DocumentRecord> findById(UUID id) {
        List<DocumentRecord> rows = jdbc.query(
                "SELECT " + DOC_COLUMNS + " FROM kb_document WHERE id = ?::uuid",
                documentMapper, id.toString());
        return rows.stream().findFirst();
    }

    public Optional<DocumentRecord> findByChecksum(String checksum) {
        List<DocumentRecord> rows = jdbc.query(
                "SELECT " + DOC_COLUMNS + " FROM kb_document WHERE checksum = ?",
                documentMapper, checksum);
        return rows.stream().findFirst();
    }

    public Optional<DocumentRecord> findByDocKeyAndVersion(String docKey, String version) {
        List<DocumentRecord> rows = jdbc.query(
                "SELECT " + DOC_COLUMNS + " FROM kb_document WHERE doc_key = ? AND version IS NOT DISTINCT FROM ?",
                documentMapper, docKey, version);
        return rows.stream().findFirst();
    }

    public List<ActiveDocument> findActiveOverlapping(String docKey, String scope, LocalDate from, LocalDate to) {
        LocalDate toOrInfinity = to != null ? to : OPEN_ENDED;
        return jdbc.query("""
                        SELECT id, version, effective_from, effective_to
                        FROM kb_document
                        WHERE doc_key = ?
                          AND status = 'ACTIVE'
                          AND COALESCE(scope, '') = COALESCE(?, '')
                          AND effective_from <= ?::date
                          AND COALESCE(effective_to, DATE '9999-12-31') >= ?::date
                        """,
                (rs, rowNum) -> new ActiveDocument(
                        rs.getObject("id", UUID.class),
                        rs.getString("version"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)),
                docKey, scope, toOrInfinity.toString(), from.toString());
    }

    public void insertDocument(DocumentRecord document) {
        jdbc.update("""
                        INSERT INTO kb_document
                          (id, doc_key, title, source_type, source_uri, checksum, version, embedding_model,
                           effective_from, effective_to, status, supersedes_doc_id, authority_level, scope,
                           topic_key, metadata)
                        VALUES
                          (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?::uuid, ?, ?, ?, ?::jsonb)
                        """,
                document.id().toString(),
                document.docKey(),
                document.title(),
                document.sourceType(),
                document.sourceUri(),
                document.checksum(),
                document.version(),
                document.embeddingModel(),
                document.effectiveFrom().toString(),
                document.effectiveTo() == null ? null : document.effectiveTo().toString(),
                document.status(),
                document.supersedesDocId() == null ? null : document.supersedesDocId().toString(),
                document.authorityLevel(),
                document.scope(),
                document.topicKey(),
                document.metadataJson());
    }

    public void insertChunks(List<ChunkInsert> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO kb_chunk
                  (id, document_id, chunk_index, chunk_strategy, content, token_count,
                   clause_id, clause_path, page_no, metadata, embedding)
                VALUES
                  (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                """;
        List<Object[]> batch = new ArrayList<>(chunks.size());
        for (ChunkInsert chunk : chunks) {
            PreparedChunk prepared = chunk.chunk();
            batch.add(new Object[]{
                    chunk.id().toString(),
                    chunk.documentId().toString(),
                    prepared.chunkIndex(),
                    prepared.strategy().dbValue(),
                    prepared.content(),
                    prepared.tokenCount(),
                    prepared.clauseId(),
                    prepared.clausePath(),
                    prepared.pageNo(),
                    chunk.metadataJson(),
                    toVectorLiteral(chunk.embedding())
            });
        }
        jdbc.batchUpdate(sql, batch);
    }

    public Map<ChunkStrategy, Integer> chunkCounts(UUID documentId) {
        return jdbc.query("""
                        SELECT chunk_strategy, count(*) AS chunk_count
                        FROM kb_chunk
                        WHERE document_id = ?::uuid
                        GROUP BY chunk_strategy
                        ORDER BY chunk_strategy
                        """,
                rs -> {
                    Map<ChunkStrategy, Integer> counts = new EnumMap<>(ChunkStrategy.class);
                    while (rs.next()) {
                        String raw = rs.getString("chunk_strategy");
                        int count = rs.getInt("chunk_count");
                        ChunkStrategy.from(raw).ifPresent(strategy -> counts.put(strategy, count));
                    }
                    return counts;
                },
                documentId.toString());
    }

    public long countAll() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM kb_document", Long.class);
        return total == null ? 0 : total;
    }

    public List<DocumentPage.DocumentRow> findPage(int limit, int offset) {
        return jdbc.query("""
                        SELECT %s,
                               (SELECT count(*) FROM kb_chunk c WHERE c.document_id = d.id) AS chunk_count
                        FROM kb_document d
                        ORDER BY d.created_at DESC, d.id
                        LIMIT ? OFFSET ?
                        """.formatted(DOC_COLUMNS),
                (rs, rowNum) -> new DocumentPage.DocumentRow(mapDocument(rs), rs.getInt("chunk_count")),
                limit, offset);
    }

    public int markSuperseded(UUID id, LocalDate effectiveTo) {
        return jdbc.update("""
                        UPDATE kb_document
                        SET status = 'SUPERSEDED', effective_to = ?::date, updated_at = now()
                        WHERE id = ?::uuid
                        """,
                effectiveTo.toString(), id.toString());
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM kb_document WHERE id = ?::uuid", id.toString());
    }

    private static DocumentRecord mapDocument(ResultSet rs) throws SQLException {
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getString("doc_key"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("source_uri"),
                rs.getString("checksum"),
                rs.getString("version"),
                rs.getString("embedding_model"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getString("status"),
                rs.getObject("supersedes_doc_id", UUID.class),
                rs.getString("authority_level"),
                rs.getString("scope"),
                rs.getString("topic_key"),
                rs.getString("metadata"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    static String toVectorLiteral(float[] vector) {
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

    public record ChunkInsert(UUID id, UUID documentId, PreparedChunk chunk, String metadataJson, float[] embedding) {
    }

    public record ActiveDocument(UUID id, String version, LocalDate effectiveFrom, LocalDate effectiveTo) {
    }
}
