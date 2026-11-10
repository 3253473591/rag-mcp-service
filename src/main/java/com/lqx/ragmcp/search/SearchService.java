package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.AbstainDiagnostics;
import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.AbstainProperties;
import com.lqx.ragmcp.config.SearchProperties;
import com.lqx.ragmcp.embedding.QueryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/search orchestration: validate -> query embedding -> vector/keyword candidates
 * -> RRF -> score-based abstention guard -> response. Retrieval failures are never converted into
 * an empty result set, and an empty result is never returned with an empty message.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_CONTENT_CHARS = 2000;

    private final QueryEmbeddingService queryEmbeddingService;
    private final SearchRepository searchRepository;
    private final SearchProperties properties;
    private final AbstainGuard abstainGuard;
    private final AnswerFallback answerFallback;

    public SearchService(QueryEmbeddingService queryEmbeddingService,
                         SearchRepository searchRepository,
                         SearchProperties properties,
                         AbstainGuard abstainGuard,
                         AnswerFallback answerFallback) {
        this.queryEmbeddingService = queryEmbeddingService;
        this.searchRepository = searchRepository;
        this.properties = properties;
        this.abstainGuard = abstainGuard;
        this.answerFallback = answerFallback;
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
            // The hybrid route requires a chunk to hit several query terms, otherwise one generic
            // term floods RRF with equally-scored chunks; an explicit keyword search keeps 1.
            int minTermHits = query.strategy() == SearchStrategy.HYBRID
                    ? properties.getKeywordMinTermHits()
                    : 1;
            keywordCandidates = searchRepository.keywordSearch(
                    query, properties.getCandidateLimit(), minTermHits);
        }

        String normalizedQuery = SectionTitleMatcher.normalizeQuestion(query.query());
        List<RrfFusion.ScoredCandidate> fused =
                RrfFusion.fuse(vectorCandidates, keywordCandidates, query.topK(),
                        candidate -> SectionTitleMatcher.boost(normalizedQuery, candidate.clausePath()),
                        1.0d, properties.getKeywordWeight());

        List<SearchResultItem> mapped = fused.stream()
                .map(scored -> toResponse(scored.candidate(), scored.score()))
                .toList();

        // The decision is made only here, from the vector route's cosine scores. It is not exposed
        // as a request field and is never delegated to a tool description or a prompt.
        AbstainDecision decision = abstainGuard.decide(vectorCandidates, query.strategy().usesVector());

        final List<SearchResultItem> results;
        final String message;
        if (!decision.abstained() && !mapped.isEmpty()) {
            results = mapped;
            message = null;
        } else {
            // A keyword-only request has no cosine evidence. It must still never return
            // "results=[] and message=null"; an empty result is honestly reported as NO_MATCH.
            decision = decision.abstained() ? decision : decision.withReason(AbstainReason.NO_MATCH);
            results = List.of();
            message = answerFallback.fallback(null, decision.reason());
        }

        long tookMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        log.info("search completed strategy={} chunkStrategy={} mode={} topK={} results={} "
                        + "abstained={} reason={} topScore={} candidateCount={} tookMs={}",
                query.strategy().apiValue(), query.chunkStrategy().dbValue(), query.versionMode(),
                query.topK(), results.size(), decision.abstained(), decision.reason(),
                decision.topScore(), decision.candidateCount(), tookMs);

        return new SearchResponse(query.query(), query.asOf(),
                query.chunkStrategy().dbValue(), query.strategy().apiValue(), tookMs,
                results, List.of(), decision.abstained(),
                decision.abstained() ? decision.reason().name() : null,
                message, diagnostics(decision));
    }

    private AbstainDiagnostics diagnostics(AbstainDecision decision) {
        AbstainProperties config = properties.getAbstain();
        return new AbstainDiagnostics(
                decision.topScore(),
                decision.secondScore(),
                decision.scoreGap(),
                decision.candidateCount(),
                decision.denseCount(),
                config.getMinTopScore(),
                config.getHighConfidenceTopScore(),
                config.getMinDenseCandidates(),
                config.getMinScoreGap(),
                config.isEnabled(),
                decision.reason().name(),
                decision.candidateScores());
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
