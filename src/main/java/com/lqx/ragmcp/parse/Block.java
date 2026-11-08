package com.lqx.ragmcp.parse;

/**
 * Unified block model produced by every parser (docs/11-retrieval-and-eval-spec.md 1.1).
 *
 * @param text         block text (heading text for heading blocks, table text for tables)
 * @param headingLevel 0 = body, 1..6 = heading level
 * @param clauseId     nullable clause id, e.g. "3.2"
 * @param clausePath   nullable clause path, e.g. "第三章 > 3.2 诊断标准"
 * @param pageNo       1-based page number, nullable (PDF only)
 * @param table        true when the block is a table
 */
public record Block(String text, int headingLevel, String clauseId, String clausePath, Integer pageNo, boolean table) {

    public Block {
        text = text == null ? "" : text;
    }

    public boolean isHeading() {
        return headingLevel > 0;
    }

    public static Block heading(int level, String text, String clauseId, String clausePath, Integer pageNo) {
        return new Block(text, level, clauseId, clausePath, pageNo, false);
    }

    public static Block paragraph(String text, Integer pageNo) {
        return new Block(text, 0, null, null, pageNo, false);
    }

    public static Block table(String text, Integer pageNo) {
        return new Block(text, 0, null, null, pageNo, true);
    }
}
