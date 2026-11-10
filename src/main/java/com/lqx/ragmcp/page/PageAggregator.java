package com.lqx.ragmcp.page;

import com.lqx.ragmcp.chunk.TokenCounter;
import com.lqx.ragmcp.parse.ClauseIds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic chunk -&gt; page aggregation for the page layer (Phase 10).
 *
 * <p><b>Rules</b> (no LLM, no heuristics that depend on the query):</p>
 * <ol>
 *   <li>Input is one document's {@code chunk_strategy='heading'} chunks. They are grouped by
 *       {@code clause_path}; a NULL/blank path is one group with the sentinel key
 *       {@value #ROOT_KEY}.</li>
 *   <li>{@code title} is the leaf segment of the clause path (text after the last {@code >})
 *       with its numeric clause prefix ({@code ^\d+(\.\d+)*\s*}) removed. A root page uses the
 *       document title.</li>
 *   <li>{@code leafClauseId} is the clause id of that leaf heading ({@link ClauseIds#extract}),
 *       falling back to the group's first chunk clause id. It exists so the strict page-side
 *       evaluation can compare a page's own clause against {@code gold.clauseId} instead of
 *       matching any clause the page happens to contain.</li>
 *   <li>{@code clauseIds} is every distinct chunk clause id in the group, in chunk order.</li>
 *   <li>{@code pageNo}/{@code pageEnd} are the min/max page numbers of the group (NULL when the
 *       parser had no page information).</li>
 *   <li>A group whose body exceeds {@code maxTokens} is split at chunk boundaries into
 *       {@code part=1..n}. A single chunk larger than the limit is hard-split by tokens as a
 *       defensive fallback, so no part can exceed the configured size.</li>
 * </ol>
 *
 * <p>Output order is the group's first chunk order, then part order; {@code pageIndex} is the
 * 0-based ordinal in that order and is stable for identical input.</p>
 */
@Component
public class PageAggregator {

    /** Group key for chunks without a clause path. */
    public static final String ROOT_KEY = "__root__";

    private static final Pattern LEADING_CLAUSE_NUMBER =
            Pattern.compile("^[\\s\\u3000]*\\d+(?:\\.\\d+)*[\\s\\u3000]*");
    private static final String BODY_SEPARATOR = "\n\n";

    private final TokenCounter tokenCounter;

    public PageAggregator(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public List<PageDraft> aggregate(String docKey,
                                     String version,
                                     String docTitle,
                                     List<PageSourceChunk> chunks,
                                     int maxTokens) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<PageSourceChunk> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparingInt(PageSourceChunk::chunkIndex));

        Map<String, List<PageSourceChunk>> groups = new LinkedHashMap<>();
        for (PageSourceChunk chunk : ordered) {
            groups.computeIfAbsent(groupKey(chunk.clausePath()), key -> new ArrayList<>()).add(chunk);
        }

        String pageKeyPrefix = docKey + "@"
                + (version == null || version.isBlank() ? "-" : version) + "#";
        List<PageDraft> pages = new ArrayList<>();
        int pageIndex = 0;
        for (Map.Entry<String, List<PageSourceChunk>> entry : groups.entrySet()) {
            Group group = describe(entry.getKey(), entry.getValue(), docTitle);
            List<Part> parts = split(entry.getValue(), maxTokens);
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                pages.add(new PageDraft(
                        pageKeyPrefix + entry.getKey(),
                        group.clausePath(),
                        group.leafClauseId(),
                        group.clauseIds(),
                        group.title(),
                        pageIndex++,
                        i + 1,
                        part.body(),
                        part.tokenCount(),
                        part.pageNo(),
                        part.pageEnd(),
                        part.sourceChunkIds()));
            }
        }
        return pages;
    }

    private static String groupKey(String clausePath) {
        return clausePath == null || clausePath.isBlank() ? ROOT_KEY : clausePath;
    }

    private Group describe(String key, List<PageSourceChunk> chunks, String docTitle) {
        boolean root = ROOT_KEY.equals(key);
        String clausePath = root ? null : key;
        String rawLeaf = root ? null : leafSegment(clausePath);
        String title = root ? rootTitle(docTitle) : leafTitle(rawLeaf);
        String leafClauseId = root ? null : leafClauseId(rawLeaf, chunks);
        Set<String> clauseIds = new LinkedHashSet<>();
        for (PageSourceChunk chunk : chunks) {
            if (chunk.clauseId() != null && !chunk.clauseId().isBlank()) {
                clauseIds.add(chunk.clauseId());
            }
        }
        return new Group(clausePath, leafClauseId, List.copyOf(clauseIds), title);
    }

    private static String rootTitle(String docTitle) {
        if (docTitle == null || docTitle.isBlank()) {
            return "正文";
        }
        return docTitle.trim();
    }

    private static String leafSegment(String clausePath) {
        int separator = clausePath.lastIndexOf('>');
        return (separator >= 0 ? clausePath.substring(separator + 1) : clausePath).trim();
    }

    private static String leafTitle(String rawLeaf) {
        if (rawLeaf == null) {
            return "正文";
        }
        String title = LEADING_CLAUSE_NUMBER.matcher(rawLeaf).replaceFirst("").trim();
        // A path leaf that is only a number ("7") must not produce an empty title.
        return title.isEmpty() ? rawLeaf : title;
    }

    private static String leafClauseId(String rawLeaf, List<PageSourceChunk> chunks) {
        String fromLeaf = ClauseIds.extract(rawLeaf);
        if (fromLeaf != null) {
            return fromLeaf;
        }
        for (PageSourceChunk chunk : chunks) {
            if (chunk.clauseId() != null && !chunk.clauseId().isBlank()) {
                return chunk.clauseId();
            }
        }
        return null;
    }

    /**
     * Greedy packing at chunk boundaries. With {@code maxTokens <= 0} the whole group is one part.
     */
    private List<Part> split(List<PageSourceChunk> chunks, int maxTokens) {
        int limit = maxTokens <= 0 ? Integer.MAX_VALUE : maxTokens;
        List<Part> parts = new ArrayList<>();
        List<PageSourceChunk> current = new ArrayList<>();
        int currentTokens = 0;
        for (PageSourceChunk chunk : chunks) {
            if (chunk.tokenCount() > limit) {
                flush(current, parts);
                current.clear();
                currentTokens = 0;
                for (String piece : tokenCounter.split(chunk.content(), limit)) {
                    parts.add(part(List.of(piece), tokenCounter.count(piece),
                            new ArrayList<>(List.of(chunk))));
                }
                continue;
            }
            if (!current.isEmpty() && currentTokens + chunk.tokenCount() > limit) {
                flush(current, parts);
                current.clear();
                currentTokens = 0;
            }
            current.add(chunk);
            currentTokens += chunk.tokenCount();
        }
        flush(current, parts);
        return parts;
    }

    private static void flush(List<PageSourceChunk> current, List<Part> parts) {
        if (current.isEmpty()) {
            return;
        }
        List<String> bodies = new ArrayList<>(current.size());
        int tokens = 0;
        for (PageSourceChunk chunk : current) {
            bodies.add(chunk.content());
            tokens += chunk.tokenCount();
        }
        parts.add(part(bodies, tokens, new ArrayList<>(current)));
    }

    private static Part part(List<String> bodies, int tokenCount, List<PageSourceChunk> chunks) {
        Integer pageNo = null;
        Integer pageEnd = null;
        List<UUID> ids = new ArrayList<>(chunks.size());
        for (PageSourceChunk chunk : chunks) {
            ids.add(chunk.chunkId());
            if (chunk.pageNo() != null) {
                pageNo = pageNo == null ? chunk.pageNo() : Math.min(pageNo, chunk.pageNo());
            }
            if (chunk.pageEnd() != null) {
                pageEnd = pageEnd == null ? chunk.pageEnd() : Math.max(pageEnd, chunk.pageEnd());
            } else if (chunk.pageNo() != null) {
                pageEnd = pageEnd == null ? chunk.pageNo() : Math.max(pageEnd, chunk.pageNo());
            }
        }
        return new Part(String.join(BODY_SEPARATOR, bodies), tokenCount, pageNo, pageEnd, ids);
    }

    private record Group(String clausePath, String leafClauseId, List<String> clauseIds, String title) {
    }

    private record Part(String body,
                        int tokenCount,
                        Integer pageNo,
                        Integer pageEnd,
                        List<UUID> sourceChunkIds) {
    }
}
