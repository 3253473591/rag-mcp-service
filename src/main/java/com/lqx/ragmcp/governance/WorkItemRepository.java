package com.lqx.ragmcp.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate persistence for {@code work_item} and {@code mandate} (Phase 1 V1 schema).
 *
 * <p>List columns are JSONB. {@code requested_doc_ids}/{@code allowed_doc_ids} preserve the
 * NULL-vs-empty distinction: SQL NULL means "all documents", {@code []} means "none".</p>
 */
@Repository
public class WorkItemRepository {

    private static final String WORK_ITEM_COLUMNS = """
            id, requester, purpose, requested_tools, requested_doc_ids, requested_top_k,
            status, decided_by, decided_at, decision_reason, created_at
            """;

    private static final String MANDATE_COLUMNS = """
            id, work_item_id, requester, allowed_tools, allowed_doc_ids, max_top_k,
            status, expires_at
            """;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkItemRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Inserts a PENDING work item and returns it with the database-generated {@code created_at}. */
    public WorkItemRecord insert(WorkItemRecord item) {
        OffsetDateTime createdAt = jdbc.queryForObject("""
                        INSERT INTO work_item
                          (id, requester, purpose, requested_tools, requested_doc_ids,
                           requested_top_k, status)
                        VALUES
                          (?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        RETURNING created_at
                        """,
                (rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class),
                item.id().toString(),
                item.requester(),
                item.purpose(),
                toJson(item.requestedTools()),
                docIdsToJson(item.requestedDocIds()),
                item.requestedTopK(),
                item.status());
        return new WorkItemRecord(
                item.id(), item.requester(), item.purpose(), item.requestedTools(),
                item.requestedDocIds(), item.requestedTopK(), item.status(),
                item.decidedBy(), item.decidedAt(), item.decisionReason(), createdAt);
    }

    public Optional<WorkItemRecord> findById(UUID id) {
        List<WorkItemRecord> rows = jdbc.query(
                "SELECT " + WORK_ITEM_COLUMNS + " FROM work_item WHERE id = ?::uuid",
                workItemMapper(), id.toString());
        return rows.stream().findFirst();
    }

    public Optional<MandateRecord> findMandateByWorkItemId(UUID workItemId) {
        List<MandateRecord> rows = jdbc.query(
                "SELECT " + MANDATE_COLUMNS + " FROM mandate WHERE work_item_id = ?::uuid",
                mandateMapper(), workItemId.toString());
        return rows.stream().findFirst();
    }

    /**
     * Conditional transition PENDING -> APPROVED. The status predicate makes concurrent approvals
     * race-safe under READ COMMITTED: exactly one caller updates a row.
     */
    public int markApproved(UUID id, String decidedBy, OffsetDateTime decidedAt, String reason) {
        return jdbc.update("""
                        UPDATE work_item
                        SET status = 'APPROVED',
                            decided_by = ?,
                            decided_at = ?::timestamptz,
                            decision_reason = ?
                        WHERE id = ?::uuid
                          AND status = 'PENDING'
                        """,
                decidedBy, decidedAt.toString(), reason, id.toString());
    }

    /** Conditional transition PENDING -> DENIED. */
    public int markDenied(UUID id, String decidedBy, OffsetDateTime decidedAt, String reason) {
        return jdbc.update("""
                        UPDATE work_item
                        SET status = 'DENIED',
                            decided_by = ?,
                            decided_at = ?::timestamptz,
                            decision_reason = ?
                        WHERE id = ?::uuid
                          AND status = 'PENDING'
                        """,
                decidedBy, decidedAt.toString(), reason, id.toString());
    }

    /**
     * Inserts the mandate. {@code uk_mandate_work_item} is the final guard against a second
     * mandate for the same WorkItem (the unique violation rolls the whole approval back).
     */
    public void insertMandate(MandateRecord mandate) {
        jdbc.update("""
                        INSERT INTO mandate
                          (id, work_item_id, requester, allowed_tools, allowed_doc_ids,
                           max_top_k, status, expires_at)
                        VALUES
                          (?::uuid, ?::uuid, ?, ?::jsonb, ?::jsonb, ?, ?, ?::timestamptz)
                        """,
                mandate.id().toString(),
                mandate.workItemId().toString(),
                mandate.requester(),
                toJson(mandate.allowedTools()),
                docIdsToJson(mandate.allowedDocIds()),
                mandate.maxTopK(),
                mandate.status(),
                mandate.expiresAt().toString());
    }

    private RowMapper<WorkItemRecord> workItemMapper() {
        return (rs, rowNum) -> mapWorkItem(rs);
    }

    private RowMapper<MandateRecord> mandateMapper() {
        return (rs, rowNum) -> mapMandate(rs);
    }

    private WorkItemRecord mapWorkItem(ResultSet rs) throws SQLException {
        return new WorkItemRecord(
                rs.getObject("id", UUID.class),
                rs.getString("requester"),
                rs.getString("purpose"),
                readStringList(rs.getString("requested_tools")),
                readUuidList(rs.getString("requested_doc_ids")),
                rs.getInt("requested_top_k"),
                rs.getString("status"),
                rs.getString("decided_by"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getString("decision_reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private MandateRecord mapMandate(ResultSet rs) throws SQLException {
        return new MandateRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("work_item_id", UUID.class),
                rs.getString("requester"),
                readStringList(rs.getString("allowed_tools")),
                readUuidList(rs.getString("allowed_doc_ids")),
                rs.getInt("max_top_k"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private List<String> readStringList(String rawJson) {
        if (rawJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(rawJson, STRING_LIST);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("数据库中的 JSONB 字段格式非法", ex);
        }
    }

    private List<UUID> readUuidList(String rawJson) {
        List<String> rawIds = readStringList(rawJson);
        if (rawIds == null) {
            return null;
        }
        return rawIds.stream().map(UUID::fromString).toList();
    }

    private String docIdsToJson(List<UUID> docIds) {
        if (docIds == null) {
            return null;
        }
        return toJson(docIds.stream().map(UUID::toString).toList());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化治理字段", ex);
        }
    }
}
