package com.lqx.ragmcp.api.dto;

/**
 * One item that did not become a wiki page.
 *
 * <p>{@code stage} is {@code parse} (model output unparseable after the parse retry; counted as
 * skipped), {@code llm} (transport/API failure; counted as failed), {@code embedding} or
 * {@code document} (document-level failure). {@code clausePath} is {@code null} for document-level
 * failures. The reason never contains credentials.</p>
 */
public record WikiCompileFailure(
        String docId,
        String clausePath,
        String stage,
        String reason) {
}
