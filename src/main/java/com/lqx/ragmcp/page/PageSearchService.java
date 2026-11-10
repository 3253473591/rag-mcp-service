package com.lqx.ragmcp.page;

import com.lqx.ragmcp.api.dto.PageDocumentInfo;
import com.lqx.ragmcp.api.dto.PageRange;
import com.lqx.ragmcp.api.dto.PageSearchRequest;
import com.lqx.ragmcp.api.dto.PageSearchResponse;
import com.lqx.ragmcp.api.dto.PageSearchResult;
import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.PageProperties;
import com.lqx.ragmcp.search.QueryVectorProvider;
import com.lqx.ragmcp.search.SearchQuery;
import com.lqx.ragmcp.search.SearchRequestValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/pages/search: validate -&gt; query embedding -&gt; page vector route -&gt; response.
 *
 * <p>This route is intentionally separate from {@code POST /api/v1/search}; it does not touch the
 * chunk response contract or ordering. It is vector-only (the A/B question is "page granularity
 * vs chunk granularity", not "hybrid vs vector"), and version/effective-interval/docIds filters
 * are applied by {@code PageRepository} in SQL exactly like the chunk vector route.</p>
 */
@Service
public class PageSearchService {

    private static final Logger log = LoggerFactory.getLogger(PageSearchService.class);

    private final QueryVectorProvider queryVectorProvider;
    private final PageRepository pageRepository;
    private final PageProperties properties;

    public PageSearchService(QueryVectorProvider queryVectorProvider,
                             PageRepository pageRepository,
                             PageProperties properties) {
        this.queryVectorProvider = queryVectorProvider;
        this.pageRepository = pageRepository;
        this.properties = properties;
    }

    public PageSearchResponse search(PageSearchRequest request) {
        return search(request, LocalDate.now());
    }

    PageSearchResponse search(PageSearchRequest request, LocalDate today) {
        long startedAt = System.nanoTime();
        String kind = normalizeKind(request == null ? null : request.kind());
        SearchQuery query = SearchRequestValidator.validate(toSearchRequest(request), today);
        float[] vector = queryVectorProvider.embedQuery(query.query());
        List<PageCandidate> candidates = pageRepository.vectorSearch(query, vector, query.topK(), kind);
        List<PageSearchResult> results = candidates.stream().map(this::toResult).toList();
        long tookMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        log.info("page search completed kind={} mode={} topK={} results={} tookMs={}",
                kind, query.versionMode(), query.topK(), results.size(), tookMs);
        return new PageSearchResponse(query.query(), query.asOf().toString(), query.topK(),
                tookMs, results);
    }

    /**
     * Default is {@code aggregate} so every existing client keeps its Phase 10 behaviour. The
     * value is validated here and applied as a SQL predicate in {@code PageRepository}.
     */
    static String normalizeKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return PageRepository.KIND_AGGREGATE;
        }
        String kind = raw.trim();
        if (PageRepository.KIND_AGGREGATE.equals(kind) || PageRepository.KIND_WIKI.equals(kind)) {
            return kind;
        }
        throw ApiException.validationField("kind", "kind 只支持 aggregate 或 wiki");
    }

    private static SearchRequest toSearchRequest(PageSearchRequest request) {
        if (request == null) {
            return null;
        }
        // chunkStrategy/strategy are irrelevant on this route and left null; the validator only
        // needs them to be absent so it applies its documented defaults.
        return new SearchRequest(request.query(), request.topK(), request.docIds(),
                request.version(), request.asOf(), request.includeSuperseded(),
                null, null, null);
    }

    private PageSearchResult toResult(PageCandidate page) {
        return new PageSearchResult(
                page.pageId().toString(),
                page.pageKey(),
                page.title(),
                page.clausePath(),
                page.leafClauseId(),
                page.clauseIds(),
                page.pageIndex(),
                page.part(),
                page.tokenCount(),
                new PageRange(page.pageNo(), page.pageEnd()),
                page.score(),
                truncate(page.body()),
                PageCitationFormatter.format(page),
                new PageDocumentInfo(
                        page.documentId().toString(),
                        page.docKey(),
                        page.docVersion(),
                        page.docStatus(),
                        page.effectiveFrom(),
                        page.effectiveTo(),
                        page.docTitle()));
    }

    private String truncate(String content) {
        if (content == null) {
            return null;
        }
        int limit = properties.getMaxContentChars();
        if (content.length() <= limit) {
            return content;
        }
        return content.substring(0, limit) + "…";
    }
}
