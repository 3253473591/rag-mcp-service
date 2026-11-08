package com.lqx.ragmcp.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One WorkItem row (docs/03-data-model.md 3.3).
 *
 * <p>{@code requestedDocIds == null} means "all documents"; an empty list means "no document".
 * The distinction is preserved end to end (JSONB NULL vs {@code []}).</p>
 */
public record WorkItemRecord(
        UUID id,
        String requester,
        String purpose,
        List<String> requestedTools,
        List<UUID> requestedDocIds,
        int requestedTopK,
        String status,
        String decidedBy,
        OffsetDateTime decidedAt,
        String decisionReason,
        OffsetDateTime createdAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DENIED = "DENIED";

    public WorkItemRecord {
        requestedTools = requestedTools == null ? List.of() : List.copyOf(requestedTools);
        requestedDocIds = requestedDocIds == null ? null : List.copyOf(requestedDocIds);
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public WorkItemRecord withDecision(String newStatus, String newDecidedBy,
                                       OffsetDateTime newDecidedAt, String newReason) {
        return new WorkItemRecord(id, requester, purpose, requestedTools, requestedDocIds,
                requestedTopK, newStatus, newDecidedBy, newDecidedAt, newReason, createdAt);
    }
}
