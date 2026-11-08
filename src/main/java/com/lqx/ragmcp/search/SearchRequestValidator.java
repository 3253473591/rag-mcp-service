package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.chunk.ChunkStrategy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Request validation and normalization for POST /api/v1/search.
 * See docs/02-api-contract.md 2.9 and docs/04-boundaries-and-errors.md 1 (B-09..B-14/B-17).
 */
public final class SearchRequestValidator {

    public static final int MAX_QUERY_CHARS = 500;
    public static final int MIN_TOPK = 1;
    public static final int MAX_TOPK = 20;
    public static final int DEFAULT_TOPK = 5;
    public static final int MAX_DOC_IDS = 50;
    public static final int MAX_VERSION_CHARS = 50;
    public static final LocalDate MIN_AS_OF = LocalDate.of(1900, 1, 1);
    public static final LocalDate MAX_AS_OF = LocalDate.of(2100, 12, 31);

    private SearchRequestValidator() {
    }

    public static SearchQuery validate(SearchRequest request, LocalDate today) {
        if (request == null) {
            throw ApiException.validation("请求体不能为空");
        }

        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw ApiException.validationField("query", "query 不能为空");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            throw ApiException.validationField("query", "query 不能超过 500 字符");
        }

        int topK = request.topK() == null ? DEFAULT_TOPK : request.topK();
        if (topK < MIN_TOPK || topK > MAX_TOPK) {
            throw ApiException.validationField("topK", "topK 必须在 1~20 之间");
        }

        List<UUID> docIds = parseDocIds(request.docIds());

        String version = request.version() == null ? null : request.version().trim();
        if (version != null && version.isEmpty()) {
            version = null;
        }
        if (version != null && version.length() > MAX_VERSION_CHARS) {
            throw ApiException.validationField("version", "version 不能超过 50 字符");
        }

        LocalDate asOf = parseAsOf(request.asOf(), today);
        boolean includeSuperseded = Boolean.TRUE.equals(request.includeSuperseded());

        ChunkStrategy chunkStrategy = parseChunkStrategy(request.chunkStrategy());
        SearchStrategy strategy = parseStrategy(request.strategy());
        SearchVersionMode versionMode =
                SearchVersionMode.forRequest(asOf, includeSuperseded, today);

        return new SearchQuery(query, topK, docIds, version, asOf, includeSuperseded,
                versionMode, chunkStrategy, strategy);
    }

    private static List<UUID> parseDocIds(List<String> rawDocIds) {
        if (rawDocIds == null || rawDocIds.isEmpty()) {
            return List.of();
        }
        if (rawDocIds.size() > MAX_DOC_IDS) {
            throw ApiException.validationField("docIds", "docIds 最多 50 个");
        }
        Set<UUID> unique = new LinkedHashSet<>();
        for (String raw : rawDocIds) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.validationField("docIds", "docIds 必须都是合法 UUID");
            }
            try {
                unique.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ex) {
                throw ApiException.validationField("docIds", "docIds 必须都是合法 UUID");
            }
        }
        return List.copyOf(unique);
    }

    private static LocalDate parseAsOf(String rawAsOf, LocalDate today) {
        if (rawAsOf == null) {
            return today;
        }
        String value = rawAsOf.trim();
        if (value.isEmpty()) {
            throw invalidAsOf();
        }
        final LocalDate parsed;
        try {
            parsed = LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalidAsOf();
        }
        if (parsed.isBefore(MIN_AS_OF) || parsed.isAfter(MAX_AS_OF)) {
            throw invalidAsOf();
        }
        return parsed;
    }

    private static ApiException invalidAsOf() {
        return ApiException.invalidAsOf(
                "asOf 必须为 YYYY-MM-DD，且在 1900-01-01 ~ 2100-12-31 之间");
    }

    private static ChunkStrategy parseChunkStrategy(String raw) {
        if (raw == null) {
            return ChunkStrategy.HEADING;
        }
        if (raw.isBlank()) {
            throw ApiException.validationField("chunkStrategy", "chunkStrategy 只允许 fixed / heading");
        }
        return ChunkStrategy.from(raw)
                .orElseThrow(() -> ApiException.validationField("chunkStrategy",
                        "chunkStrategy 只允许 fixed / heading"));
    }

    private static SearchStrategy parseStrategy(String raw) {
        if (raw == null) {
            return SearchStrategy.HYBRID;
        }
        if (raw.isBlank()) {
            throw ApiException.validationField("strategy", "strategy 只允许 hybrid / vector / keyword");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return SearchStrategy.from(normalized)
                .orElseThrow(() -> ApiException.validationField("strategy",
                        "strategy 只允许 hybrid / vector / keyword"));
    }
}
