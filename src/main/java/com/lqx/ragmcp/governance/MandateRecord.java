package com.lqx.ragmcp.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One mandate row (docs/03-data-model.md 3.4). v1 only creates ACTIVE mandates; expiry is the
 * logical state {@code now() >= expires_at} and is evaluated by Phase 5, not written back here.
 *
 * <p>{@code allowedDocIds == null} means "all documents"; {@code []} means "no document".</p>
 */
public record MandateRecord(
        UUID id,
        UUID workItemId,
        String requester,
        List<String> allowedTools,
        List<UUID> allowedDocIds,
        int maxTopK,
        String status,
        OffsetDateTime expiresAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public MandateRecord {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        allowedDocIds = allowedDocIds == null ? null : List.copyOf(allowedDocIds);
    }
}
