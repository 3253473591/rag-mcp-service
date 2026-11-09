package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.SearchProperties;
import com.lqx.ragmcp.embedding.QueryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/search orchestration: validate -> query embedding -> vector/keyword candidates
 * -> RRF -> response. Retrieval failures are never converted into an empty result set.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_CONTENT_CHARS = 2000;

    private final QueryEmbeddingService queryEmbeddingService;
    private final SearchRepository searchRepository;
    private final SearchProperties properties;

    public SearchService(QueryEmbeddingService queryEmbeddingService,
                         SearchRepository searchRepository,
                         SearchProperties properties) {
        this.queryEmbeddingService = queryEmbeddingService;
        this.searchRepository = searchRepository;
        this.properties = properties;
    }

    public SearchResponse search(SearchRequest request) {
        return search(request, LocalDate.now());
    }

    SearchResponse search(SearchRequest request, LocalDate today) {
        long startedAt = System.nanoTime();
        SearchQuery query = SearchRequestValidator.validate(request, today);

        List<SearchCandidate> vectorCandidates = List.of();
        if (query.strategy().usesVector()) {
            float[] queryVector = embedQuery(query.query());
            vectorCandidates = searchRepository.vectorSearch(
                    query, queryVector, properties.getCandidateLimit());
        }

        List<SearchCandidate> keywordCandidates = List.of();
        if (query.strategy().usesKeyword()) {
            keywordCandidates = searchRepository.keywordSearch(query, properties.getCandidateLimit());
        }

        List<RrfFusion.ScoredCandidate> fused =
                RrfFusion.fuse(vectorCandidates, keywordCandidates, query.topK());

        List<SearchResultItem> results = fused.stream()
                .map(scored -> toResponse(scored.candidate(), scored.score()))
                .toList();

        long tookMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        log.info("search completed strategy={} chunkStrategy={} mode={} topK={} results={} tookMs={}",
                query.strategy().apiValue(), query.chunkStrategy().dbValue(), query.versionMode(),
                query.topK(), results.size(), tookMs);

        return new SearchResponse(query.query(), query.asOf(),
                query.chunkStrategy().dbValue(), query.strategy().apiValue(), tookMs,
                results, List.of());
    }

    private float[] embedQuery(String query) {
        final float[] vector;
        try {
            vector = queryEmbeddingService.embedQuery(query);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("query embedding failed type={}", ex.getClass().getSimpleName());
            throw ApiException.embeddingUnavailable("embedding 服务不可用，请确认 Ollama 与 bge-m3 已就绪");
        }
        if (vector == null || vector.length != queryEmbeddingService.dimension()) {
            throw ApiException.embeddingUnavailable(
                    "embedding 维度不是 " + queryEmbeddingService.dimension() + "，请检查模型配置");
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (value != 0.0f) {
                nonZero = true;
                break;
            }
        }
        if (!nonZero) {
            throw ApiException.embeddingUnavailable("embedding 返回零向量，拒绝检索");
        }
        return vector;
    }

    private static SearchResultItem toResponse(SearchCandidate candidate, double score) {
        return new SearchResultItem(
                candidate.chunkId().toString(),
                candidate.docId().toString(),
                candidate.docKey(),
                candidate.docVersion(),
                candidate.docStatus(),
                candidate.effectiveFrom(),
                candidate.effectiveTo(),
                candidate.title(),
                candidate.clauseId(),
                candidate.clausePath(),
                candidate.pageNo(),
                candidate.pageEnd(),
                score,
                truncate(candidate.content()),
                CitationFormatter.format(candidate));
    }

    static String truncate(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS) + "…";
    }
}
