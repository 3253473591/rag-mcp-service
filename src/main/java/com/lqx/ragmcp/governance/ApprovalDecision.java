package com.lqx.ragmcp.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Validated, narrowed mandate scope produced by {@link MandatePolicy}. */
public record ApprovalDecision(
        List<String> allowedTools,
        List<UUID> allowedDocIds,
        int maxTopK,
        int ttlSeconds,
        OffsetDateTime expiresAt,
        String reason) {

    public ApprovalDecision {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        allowedDocIds = allowedDocIds == null ? null : List.copyOf(allowedDocIds);
    }
}
