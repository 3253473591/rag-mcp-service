package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.config.SearchProperties;
import com.lqx.ragmcp.embedding.QueryEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    private QueryEmbeddingService queryEmbeddingService;
    private SearchRepository repository;
    private SearchProperties properties;
    private SearchService service;

    @BeforeEach
    void setUp() {
        queryEmbeddingService = mock(QueryEmbeddingService.class);
        repository = mock(SearchRepository.class);
        properties = new SearchProperties();
        service = new SearchService(queryEmbeddingService, repository, properties,
                new AbstainGuard(properties), new AnswerFallback());
    }

    @Test
    void keywordOnlyDoesNotCallEmbeddingAndTruncatesLongContent() {
        SearchCandidate candidate = new SearchCandidate(
                UUID.randomUUID(), UUID.randomUUID(), "doc-key", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null, "标题", "3.2", "第三章", 12, 12,
                "x".repeat(2500), "heading", 0, 0.4);
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of(candidate));

        SearchResponse response = service.search(new SearchRequest(
                "关键词", 5, null, null, "2026-11-09", null, null, "keyword", true), TODAY);

        verify(queryEmbeddingService, never()).embedQuery(any());
        assertThat(response.strategy()).isEqualTo("keyword");
        assertThat(response.chunkStrategy()).isEqualTo("heading");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).content()).hasSize(2001).endsWith("…");
        assertThat(response.results().get(0).score()).isCloseTo(1.0d / 61.0d,
                org.assertj.core.api.Assertions.within(1e-12));
        assertThat(response.conflicts()).isEmpty();
        assertThat(response.abstained()).isFalse();
        assertThat(response.diagnostics().reason()).isEqualTo("NONE");
        assertResponseContract(response);
    }

    @Test
    void vectorRouteEmbeddingFailureIs503AndDoesNotQueryDatabase() {
        when(queryEmbeddingService.embedQuery(any()))
                .thenThrow(ApiException.embeddingUnavailable("ollama down"));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
        verify(repository, never()).vectorSearch(any(), any(), anyInt());
    }

    @Test
    void vectorRouteTimeoutIs504() {
        when(queryEmbeddingService.embedQuery(any()))
                .thenThrow(ApiException.embeddingTimeout("timeout"));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_TIMEOUT));
    }

    @Test
    void wrongEmbeddingDimensionIs503() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(new float[512]);

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void databaseFailureIsNotSwallowedAsEmptyResult() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        when(repository.vectorSearch(any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void vectorAndKeywordRoutesAreBothUsedForHybrid() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        SearchCandidate a = candidate(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000101", "3.2", "第三章 > 3.2", 0, "内容 A", 0.8);
        SearchCandidate b = candidate(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000102", "3.2", "第三章 > 3.2", 0, "内容 B", 0.7);
        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of(a));
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of(b));

        SearchResponse response = service.search(new SearchRequest(
                "q", 5, null, null, "2026-11-09", null, null, "hybrid", null), TODAY);

        verify(repository).vectorSearch(any(), any(), anyInt());
        verify(repository).keywordSearch(any(), anyInt(), anyInt());
        assertThat(response.results()).hasSize(2);
        assertThat(response.abstained()).isFalse();
        assertThat(response.diagnostics().topScore()).isEqualTo(0.8);
        assertResponseContract(response);
    }

    @Test
    void q04LikeQueryPromotesSectionWhoseLeafTitleIsNamedByTheQuestion() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        SearchCandidate wrong = candidate(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000101",
                "4.3", "4  高血压诊断与评估 > 4.3  初诊评估", 0,
                "评估是确定高血压治疗策略的基础。", 0.82);
        SearchCandidate right = candidate(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000102",
                "4.2.2", "4  高血压诊断与评估 > 4.2  高血压诊断标准 > 4.2.2  诊断流程", 1,
                "当首诊诊室血压≥140/90 mmHg 时，应明确诊断或转诊。", 0.80);
        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of(wrong, right));
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResponse response = service.search(new SearchRequest(
                "高血压的诊断流程是什么？", 5, null, null, "2026-11-09",
                null, null, "hybrid", null), TODAY);

        assertThat(response.results()).extracting(SearchResultItem::clauseId)
                .containsExactly("4.2.2", "4.3");
        assertThat(response.results().get(0).content()).contains("明确诊断");
        assertResponseContract(response);
    }

    @Test
    void lowTopCosineAbstainsWithNoMatchDiagnostics() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of(
                candidate("00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000101", null, null, 0, "无关 A", 0.40),
                candidate("00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000102", null, null, 0, "无关 B", 0.38)));
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResponse response = service.search(vectorRequest("公司年假有多少天？"), TODAY);

        assertThat(response.abstained()).isTrue();
        assertThat(response.abstainReason()).isEqualTo("NO_MATCH");
        assertThat(response.results()).isEmpty();
        assertThat(response.message()).isEqualTo(AnswerFallback.NO_MATCH_MESSAGE);
        assertThat(response.diagnostics().topScore()).isEqualTo(0.40);
        assertThat(response.diagnostics().candidateCount()).isEqualTo(2);
        assertThat(response.diagnostics().reason()).isEqualTo("NO_MATCH");
        assertResponseContract(response);
    }

    @Test
    void ambiguousGrayZoneAbstainsWithTooNoisyDiagnostics() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        when(repository.vectorSearch(any(), any(), anyInt()))
                .thenReturn(denseCandidates(0.60, 0.002, 12));
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResponse response = service.search(vectorRequest("患者张三最近的血压记录是多少？"), TODAY);

        assertThat(response.abstained()).isTrue();
        assertThat(response.abstainReason()).isEqualTo("TOO_NOISY");
        assertThat(response.results()).isEmpty();
        assertThat(response.message()).isEqualTo(AnswerFallback.TOO_NOISY_MESSAGE);
        assertThat(response.diagnostics().topScore()).isEqualTo(0.60);
        assertThat(response.diagnostics().denseCount()).isEqualTo(12);
        assertThat(response.diagnostics().secondScore()).isEqualTo(0.598);
        assertThat(response.diagnostics().reason()).isEqualTo("TOO_NOISY");
        assertResponseContract(response);
    }

    @Test
    void confidentTopScoreIsNotAbstainedEvenWhenManyCandidatesAreClose() {
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        when(repository.vectorSearch(any(), any(), anyInt()))
                .thenReturn(denseCandidates(0.70, 0.005, 12));
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResponse response = service.search(vectorRequest("高血压的诊断标准是什么？"), TODAY);

        assertThat(response.abstained()).isFalse();
        assertThat(response.results()).hasSize(5);
        assertThat(response.diagnostics().reason()).isEqualTo("NONE");
        assertThat(response.diagnostics().topScore()).isEqualTo(0.70);
        assertResponseContract(response);
    }

    @Test
    void disabledGuardReturnsResultsBelowThresholdButKeepsDiagnostics() {
        properties.getAbstain().setEnabled(false);
        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of(
                candidate("00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000101", null, null, 0, "低分内容", 0.20)));
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResponse response = service.search(vectorRequest("q"), TODAY);

        assertThat(response.abstained()).isFalse();
        assertThat(response.results()).hasSize(1);
        assertThat(response.diagnostics().enabled()).isFalse();
        assertThat(response.diagnostics().topScore()).isEqualTo(0.20);
        assertThat(response.diagnostics().reason()).isEqualTo("NONE");
        assertResponseContract(response);
    }

    @Test
    void emptyKeywordResultsStillCarryNoMatchMessage() {
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResponse response = service.search(new SearchRequest(
                "zzz-not-in-kb", 5, null, null, "2026-11-09", null, null, "keyword", null), TODAY);

        assertThat(response.results()).isEmpty();
        assertThat(response.abstained()).isTrue();
        assertThat(response.abstainReason()).isEqualTo("NO_MATCH");
        assertThat(response.message()).isNotBlank();
        assertThat(response.diagnostics().reason()).isEqualTo("NO_MATCH");
        assertThat(response.diagnostics().candidateCount()).isZero();
        assertResponseContract(response);
    }

    @Test
    void noResponseEverHasEmptyResultsAndEmptyMessage() {
        List<SearchResponse> responses = new ArrayList<>();
        responses.add(service.search(new SearchRequest(
                "zzz", 5, null, null, "2026-11-09", null, null, "keyword", null), TODAY));

        when(queryEmbeddingService.dimension()).thenReturn(1024);
        when(queryEmbeddingService.embedQuery(any())).thenReturn(unitVector());
        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of());
        when(repository.keywordSearch(any(), anyInt(), anyInt())).thenReturn(List.of());
        responses.add(service.search(vectorRequest("q"), TODAY));

        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of(
                candidate("00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000101", null, null, 0, "x", 0.10)));
        responses.add(service.search(vectorRequest("q"), TODAY));

        for (SearchResponse response : responses) {
            assertResponseContract(response);
        }
    }

    /**
     * Phase 7 contract: an empty result set is never paired with an empty message.
     */
    private static void assertResponseContract(SearchResponse response) {
        if (response.results().isEmpty()) {
            assertThat(response.message()).isNotBlank();
            assertThat(response.abstained()).isTrue();
            assertThat(response.abstainReason()).isIn("NO_MATCH", "TOO_NOISY");
            assertThat(response.diagnostics()).isNotNull();
            assertThat(response.diagnostics().reason()).isEqualTo(response.abstainReason());
        } else {
            assertThat(response.abstained()).isFalse();
            assertThat(response.abstainReason()).isNull();
        }
    }

    private static SearchCandidate candidate(String chunkId, String docId, String clauseId,
                                             String clausePath, int chunkIndex, String content,
                                             double routeScore) {
        return new SearchCandidate(
                UUID.fromString(chunkId),
                UUID.fromString(docId),
                "hypertension-guideline",
                "2025",
                "ACTIVE",
                LocalDate.of(2025, 1, 1),
                null,
                "hypertension-2025",
                clauseId,
                clausePath,
                4,
                4,
                content,
                "heading",
                chunkIndex,
                routeScore);
    }

    private static List<SearchCandidate> denseCandidates(double topScore, double step, int count) {
        List<SearchCandidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candidates.add(candidate(
                    String.format("00000000-0000-0000-0000-%012d", i + 1),
                    String.format("00000000-0000-0000-0000-%012d", 1001 + i),
                    null, null, i, "候选 " + i, topScore - step * i));
        }
        return candidates;
    }

    private SearchRequest vectorRequest() {
        return vectorRequest("q");
    }

    private SearchRequest vectorRequest(String query) {
        return new SearchRequest(query, 5, null, null, "2026-11-09",
                null, null, "vector", null);
    }

    private static float[] unitVector() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }
}
