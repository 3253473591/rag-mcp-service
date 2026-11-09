package com.lqx.ragmcp.mcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate persistence for {@code mcp_audit_log}. The table has no foreign keys, so an audit
 * row survives mandate/work-item deletion.
 */
@Repository
public class McpAuditRepository {

    private final JdbcTemplate jdbc;

    public McpAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(McpAuditEntry entry) {
        jdbc.update("""
                        INSERT INTO mcp_audit_log
                          (tool_name, work_item_id, mandate_id, caller_fingerprint, args_hash,
                           status, error_code, latency_ms)
                        VALUES
                          (?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                        """,
                entry.toolName(),
                entry.workItemId() == null ? null : entry.workItemId().toString(),
                entry.mandateId() == null ? null : entry.mandateId().toString(),
                entry.callerFingerprint(),
                entry.argsHash(),
                entry.status(),
                entry.errorCode(),
                entry.latencyMs());
    }
}
