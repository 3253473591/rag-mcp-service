package com.lqx.ragmcp.wiki;

import com.lqx.ragmcp.page.PageSourceChunk;

import java.util.List;
import java.util.UUID;

/**
 * One leaf section to be compiled into a wiki page.
 *
 * <p>A section is the same grouping unit as the deterministic page layer: all
 * {@code chunk_strategy='heading'} chunks of one document that share a {@code clause_path}
 * (NULL/blank is the root section). The wiki page is one row, not split into parts, so the
 * acceptance metric compares one section against the chunk set of that section.</p>
 */
public record WikiSection(
        UUID documentId,
        String docKey,
        String docVersion,
        String docTitle,
        String pageKey,
        String clausePath,
        String leafClauseId,
        List<String> clauseIds,
        int sectionIndex,
        Integer pageNo,
        Integer pageEnd,
        List<PageSourceChunk> chunks) {

    /** Short, human-readable identifier used in logs and failure reports. */
    public String sectionLabel() {
        return clausePath == null || clausePath.isBlank() ? "__root__" : clausePath;
    }
}
