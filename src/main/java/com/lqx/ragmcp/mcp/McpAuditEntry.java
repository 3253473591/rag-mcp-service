package com.lqx.ragmcp.mcp;

import java.util.UUID;

/**
 * One {@code mcp_audit_log} row (docs/03-data-model.md 3.5).
 *
 * <p>The audit row never contains the query text, document content or the raw token. The caller is
 * identified only by {@code callerFingerprint} and the arguments only by {@code argsHash}.</p>
 */
public record McpAuditEntry(
        String toolName,
        UUID workItemId,
        UUID mandateId,
        String callerFingerprint,
        String argsHash,
        String status,
        String errorCode,
        int latencyMs) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
}
