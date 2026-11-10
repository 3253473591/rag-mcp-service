package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.AbstainDiagnostics;
import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.AbstainProperties;
import com.lqx.ragmcp.config.RerankProperties;
import com.lqx.ragmcp.config.SearchProperties;
import com.lqx.ragmcp.embedding.QueryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        RerankProperties rerankConfig = properties.getRerank();
        boolean rerankEnabled = rerankConfig.isEnabled();
        // The rerank window is never smaller than topK: every returned result must be ordered by the
        // score the API documents. When rerank is off this collapses to the Phase 8 behaviour.
        int rerankWindow = Math.max(query.topK(), rerankConfig.getTopN());
        int fusionLimit = rerankEnabled ? rerankWindow : query.topK();
        // With rerank on, the title signal is applied inside the rerank score. Applying it to the
        // fusion ordering as well would count the same evidence twice and make "all weights zero"
        // differ from the plain RRF order.
        List<RrfFusion.ScoredCandidate> fused = RrfFusion.fuse(
                vectorCandidates, keywordCandidates, fusionLimit,
                rerankEnabled
                        ? candidate -> 0.0d
                        : candidate -> SectionTitleMatcher.boost(normalizedQuery, candidate.clausePath()),
                1.0d, properties.getKeywordWeight());

        final List<CandidateReranker.Result> ordered;
        if (rerankEnabled) {
            List<String> queryTerms = KeywordTerms.extract(
                    KeywordEscaper.keywordText(query.query(), properties.getKeywordMaxChars()),
                    properties.getKeywordMaxTerms());
            ordered = CandidateReranker.rerank(fused, cosineByChunkId(vectorCandidates),
                    queryTerms, normalizedQuery, rerankConfig, rerankWindow);
        } else {
            ordered = fused.stream()
                    .map(scored -> new CandidateReranker.Result(
                            scored.candidate(), scored.score(), scored.score()))
                    .toList();
        }

        List<SearchResultItem> mapped = ordered.stream()
                .limit(query.topK())
                .map(result -> toResponse(result, rerankEnabled ? result.rerankScore() : null))
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
        log.info("search completed strategy={} chunkStrategy={} mode={} topK={} rerank={} results={} "
                        + "abstained={} reason={} topScore={} candidateCount={} tookMs={}",
                query.strategy().apiValue(), query.chunkStrategy().dbValue(), query.versionMode(),
                query.topK(), rerankEnabled, results.size(), decision.abstained(), decision.reason(),
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

    /**
     * Cosine similarity is only defined for candidates returned by the vector route. A fused
     * candidate that only the keyword route found has no semantic evidence, so it is simply
     * absent from this map (the reranker then contributes {@code 0} for it).
     */
    private static Map<UUID, Double> cosineByChunkId(List<SearchCandidate> vectorCandidates) {
        if (vectorCandidates == null || vectorCandidates.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Double> scores = new HashMap<>(vectorCandidates.size());
        for (SearchCandidate candidate : vectorCandidates) {
            if (candidate != null && candidate.chunkId() != null) {
                scores.put(candidate.chunkId(), candidate.routeScore());
            }
        }
        return scores;
    }

    private static SearchResultItem toResponse(CandidateReranker.Result result, Double rerankScore) {
        SearchCandidate candidate = result.candidate();
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
                result.rrfScore(),
                rerankScore,
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
