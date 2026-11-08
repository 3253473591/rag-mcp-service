package com.lqx.ragmcp.eval;

import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.search.SearchService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a QA dataset through the real retrieval pipeline (hybrid search, docs/11 4.4).
 *
 * <p>Each QA item may override the run-wide {@code asOf} / {@code includeSuperseded}; this is how
 * historical questions are answered against a superseded version. There is intentionally no
 * HTTP endpoint yet: {@code scripts/eval.ps1} is the reproducible Windows runner, and this service
 * backs T-INT-EVAL-01.</p>
 */
@Service
public class EvalService {

    private final SearchService searchService;

    public EvalService(SearchService searchService) {
        this.searchService = searchService;
    }

    public EvalRunResult run(EvalDataset dataset,
                             ChunkStrategy chunkStrategy,
                             LocalDate defaultAsOf,
                             int topK) {
        EvalScorer scorer = new EvalScorer(dataset.answerableCount(), dataset.unanswerableCount());
        List<EvalQuestionResult> questionResults = new ArrayList<>();

        for (EvalQaItem item : dataset.items()) {
            String asOf = hasText(item.asOf()) ? item.asOf().trim() : defaultAsOf.toString();
            boolean includeSuperseded = Boolean.TRUE.equals(item.includeSuperseded());
            long startedAt = System.nanoTime();
            try {
                SearchRequest request = new SearchRequest(
                        item.question(),
                        topK,
                        null,
                        null,
                        asOf,
                        includeSuperseded,
                        chunkStrategy.dbValue(),
                        "hybrid",
                        false);
                SearchResponse response = searchService.search(request);
                double latencyMs = elapsedMs(startedAt);
                List<SearchResultItem> results = response.results() == null
                        ? List.of()
                        : List.copyOf(response.results());
                if (item.answerable()) {
                    int rank = GoldMatcher.findRank(results, item.gold());
                    scorer.recordAnswerableRank(rank, latencyMs);
                    questionResults.add(new EvalQuestionResult(item.id(), true, rank, results.size(),
                            false, null, latencyMs, asOf, includeSuperseded, results));
                } else {
                    scorer.recordUnanswerable(results.size(), latencyMs);
                    questionResults.add(new EvalQuestionResult(item.id(), false, 0, results.size(),
                            false, null, latencyMs, asOf, includeSuperseded, results));
                }
            } catch (RuntimeException ex) {
                double latencyMs = elapsedMs(startedAt);
                scorer.recordError(item.answerable());
                questionResults.add(new EvalQuestionResult(item.id(), item.answerable(), 0, 0,
                        true, describe(ex), latencyMs, asOf, includeSuperseded, List.of()));
            }
        }
        return new EvalRunResult(scorer.summary(), List.copyOf(questionResults));
    }

    private static String describe(RuntimeException ex) {
        if (ex instanceof ApiException apiException) {
            return apiException.errorCode().name() + ": " + apiException.getMessage();
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private static double elapsedMs(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 10_000.0d) / 100.0d;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
