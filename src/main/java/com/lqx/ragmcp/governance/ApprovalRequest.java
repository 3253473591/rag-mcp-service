package com.lqx.ragmcp.governance;

import java.util.List;

/**
 * Normalized approve body. {@code allowedDocIdsProvided} records whether the JSON field was
 * present at all, because the contract distinguishes "absent" (400 on a null request scope) from
 * an explicit JSON {@code null} (all documents).
 */
public record ApprovalRequest(
        Integer ttlSeconds,
        List<String> allowedTools,
        boolean allowedDocIdsProvided,
        List<String> allowedDocIds,
        Integer maxTopK,
        String reason) {
}
