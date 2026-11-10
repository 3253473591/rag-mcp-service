package com.lqx.ragmcp.page;

import java.util.List;
import java.util.UUID;

/**
 * One page part produced by {@link PageAggregator} before embedding and persistence.
 *
 * <p>{@code pageKey} identifies the leaf section, {@code part} numbers the oversize split
 * ({@code 1..n}), and {@code sourceChunkIds} records exactly which chunks produced this row.</p>
 */
public record PageDraft(
        String pageKey,
        String clausePath,
        String leafClauseId,
        List<String> clauseIds,
        String title,
        int pageIndex,
        int part,
        String body,
        int tokenCount,
        Integer pageNo,
        Integer pageEnd,
        List<UUID> sourceChunkIds) {

    /** Text that is embedded for this page: leaf title + body. */
    public String embeddingText() {
        return title + "\n" + body;
    }
}
