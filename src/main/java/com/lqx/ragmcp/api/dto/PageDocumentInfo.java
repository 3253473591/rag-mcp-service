package com.lqx.ragmcp.api.dto;

import java.time.LocalDate;

/** Document metadata attached to a page hit (same fields the chunk search response exposes). */
public record PageDocumentInfo(
        String docId,
        String docKey,
        String docVersion,
        String docStatus,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String title) {
}
