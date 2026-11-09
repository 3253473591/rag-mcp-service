package com.lqx.ragmcp.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.config.McpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one audit row per MCP {@code tools/call} (success or failure).
 *
 * <p>Audit failures are swallowed and logged at ERROR level: the tool result is never lost because
 * the audit table is unavailable. The query text and document content are never written; only a
 * SHA-256 of the JSON arguments is stored.</p>
 */
@Service
public class McpAuditService {

    private static final Logger log = LoggerFactory.getLogger(McpAuditService.class);

    private final McpAuditRepository repository;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpAuditService(McpAuditRepository repository,
                           McpProperties properties,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** SHA-256 (64 hex chars) of the JSON arguments; never the arguments themselves. */
    public String argsHash(Object arguments) {
        Object payload = arguments == null ? Map.of() : arguments;
        try {
            return McpAuth.sha256Hex(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            // The arguments came from JSON already; this is defensive only.
            return McpAuth.sha256Hex("{}");
        }
    }

    /** SHA-256[0..12] of the configured MCP token; {@code null} when unconfigured. */
    public String callerFingerprint() {
        return McpAuth.fingerprint(properties.getAuthToken());
    }

    public void record(String toolName,
                       UUID workItemId,
                       UUID mandateId,
                       String argsHash,
                       String status,
                       String errorCode,
                       long latencyMs) {
        int latency = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, latencyMs));
        McpAuditEntry entry = new McpAuditEntry(
                toolName,
                workItemId,
                mandateId,
                callerFingerprint(),
                argsHash,
                status,
                errorCode,
                latency);
        try {
            repository.insert(entry);
        } catch (RuntimeException ex) {
            // docs/08-governance.md 7: audit failure must not block the main flow.
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("tool", toolName);
            fields.put("mandateId", mandateId);
            fields.put("status", status);
            fields.put("errorCode", errorCode);
            log.error("failed to write mcp_audit_log {}", fields, ex);
        }
    }
}
