package com.lqx.ragmcp.eval;

import com.lqx.ragmcp.api.dto.AbstainDiagnostics;
import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.search.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Per-question asOf override and error accounting in the eval orchestration. */
@ExtendWith(MockitoExtension.class)
class EvalServiceTest {

    @Mock
    private SearchService searchService;

    @Test
    void perQuestionAsOfOverridesDefaultAndErrorsAreCountedSeparately() {
        EvalQaItem historical = new EvalQaItem("Q1", "2024 诊室血压标准", true,
                new EvalGold("hypertension-guideline", "2024", "4.2.1", List.of(1, 1)),
                "2024-06-01", true);
        EvalQaItem failing = new EvalQaItem("Q2", "当前指南诊断标准", true,
                new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4)),
                null, null);
        EvalQaItem noAnswer = new EvalQaItem("N1", "公司年假有多少天？", false, null, null, null);
        EvalDataset dataset = EvalDataset.of(List.of(historical, failing, noAnswer));

        when(searchService.search(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (request.query().startsWith("2024")) {
                return response(request, List.of(result("hypertension-guideline", "4.2.1", 1)));
            }
            if (request.query().startsWith("当前")) {
                throw ApiException.embeddingUnavailable("fake embedding unavailable");
            }
            return response(request, List.of());
        });

        EvalRunResult run = new EvalService(searchService)
                .run(dataset, ChunkStrategy.HEADING, LocalDate.of(2026, 11, 9), 5);

        EvalSummary summary = run.summary();
        assertThat(summary.qaCount()).isEqualTo(3);
        assertThat(summary.answerableCount()).isEqualTo(2);
        assertThat(summary.answered()).isEqualTo(1);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.answerableErrors()).isEqualTo(1);
        assertThat(summary.hitAt1()).isEqualTo(1.0);
        assertThat(summary.mrr()).isEqualTo(1.0);
        assertThat(summary.noAnswerAccuracy()).isEqualTo(1.0);

        assertThat(run.questions()).hasSize(3);
        EvalQuestionResult historicalResult = run.questions().get(0);
        assertThat(historicalResult.rank()).isEqualTo(1);
        assertThat(historicalResult.asOf()).isEqualTo("2024-06-01");
        assertThat(historicalResult.includeSuperseded()).isTrue();
        EvalQuestionResult failingResult = run.questions().get(1);
        assertThat(failingResult.error()).isTrue();
        assertThat(failingResult.errorMessage()).contains("EMBEDDING_UNAVAILABLE");
        assertThat(failingResult.asOf()).isEqualTo("2026-11-09");
        EvalQuestionResult noAnswerResult = run.questions().get(2);
        assertThat(noAnswerResult.answerable()).isFalse();
        assertThat(noAnswerResult.resultCount()).isZero();

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService, org.mockito.Mockito.times(3)).search(captor.capture());
        SearchRequest historicalRequest = captor.getAllValues().get(0);
        assertThat(historicalRequest.asOf()).isEqualTo("2024-06-01");
        assertThat(historicalRequest.includeSuperseded()).isTrue();
        assertThat(historicalRequest.chunkStrategy()).isEqualTo("heading");
        assertThat(historicalRequest.topK()).isEqualTo(5);
        assertThat(captor.getAllValues().get(1).asOf()).isEqualTo("2026-11-09");
    }

    @Test
    void abstainedUnanswerableCountsAsCorrectWithoutChangingGoldOrMatching() {
        EvalQaItem answerable = new EvalQaItem("Q1", "诊室血压标准", true,
                new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4)),
                null, null);
        EvalQaItem noAnswer = new EvalQaItem("N1", "公司年假有多少天？", false, null, null, null);
        EvalDataset dataset = EvalDataset.of(List.of(answerable, noAnswer));

        when(searchService.search(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (request.query().startsWith("公司")) {
                return response(request, List.of(), true, "NO_MATCH",
                        com.lqx.ragmcp.search.AnswerFallback.NO_MATCH_MESSAGE);
            }
            return response(request, List.of(result("hypertension-guideline", "4.2.1", 4)));
        });

        EvalSummary summary = new EvalService(searchService)
                .run(dataset, ChunkStrategy.HEADING, LocalDate.of(2026, 11, 9), 5).summary();

        // The unanswerable question is recorded by its returned result count (0), so abstention is
        // naturally correct. The gold set and matching rules are untouched.
        assertThat(summary.falsePositiveRate()).isZero();
        assertThat(summary.noAnswerAccuracy()).isEqualTo(1.0);
        assertThat(summary.hitAt5()).isEqualTo(1.0);
    }

    private static SearchResponse response(SearchRequest request, List<SearchResultItem> results) {
        return response(request, results, false, null, null);
    }

    private static SearchResponse response(SearchRequest request, List<SearchResultItem> results,
                                           boolean abstained, String abstainReason, String message) {
        AbstainDiagnostics diagnostics = new AbstainDiagnostics(
                0.0d, 0.0d, 0.0d, results.size(), 0,
                0.55d, 0.63d, 8, 0.02d, true,
                abstained ? abstainReason : "NONE", List.of());
        return new SearchResponse(request.query(), LocalDate.parse(request.asOf()),
                request.chunkStrategy(), request.strategy(), 1L, results, List.of(),
                abstained, abstainReason, message, diagnostics);
    }

    private static SearchResultItem result(String docKey, String clauseId, Integer pageNo) {
        return new SearchResultItem("chunk-1", "doc-1", docKey, "2024", "SUPERSEDED",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "title", clauseId,
                "chapter > " + clauseId, pageNo, pageNo, 0.032, "content", "citation");
    }
}
