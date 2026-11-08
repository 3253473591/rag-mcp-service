package com.lqx.ragmcp.parse;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

/**
 * Tracks the heading hierarchy while parsing and builds clause paths such as
 * {@code 第三章 > 3.2 诊断标准}. The heading text itself is never a chunk.
 */
public final class HeadingContext {

    public record Heading(int level, String text, String clauseId) {
    }

    private final Deque<Heading> stack = new ArrayDeque<>();

    public Block heading(int level, String text, Integer pageNo) {
        int normalizedLevel = Math.max(1, Math.min(6, level));
        String title = text == null ? "" : text.trim();
        while (!stack.isEmpty() && stack.peekLast().level() >= normalizedLevel) {
            stack.removeLast();
        }
        String parentPath = stack.stream().map(Heading::text).collect(Collectors.joining(" > "));
        String path = parentPath.isEmpty() ? title : parentPath + " > " + title;
        String clauseId = ClauseIds.extract(title);
        stack.addLast(new Heading(normalizedLevel, title, clauseId));
        return Block.heading(normalizedLevel, title, clauseId, path, pageNo);
    }

    public String currentPath() {
        String path = stack.stream().map(Heading::text).collect(Collectors.joining(" > "));
        return path.isEmpty() ? null : path;
    }

    public String currentClauseId() {
        Heading last = stack.peekLast();
        return last == null ? null : last.clauseId();
    }

    public boolean hasHeadings() {
        return !stack.isEmpty();
    }

    public void reset() {
        stack.clear();
    }
}
