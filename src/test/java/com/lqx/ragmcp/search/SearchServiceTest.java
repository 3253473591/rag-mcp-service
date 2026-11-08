package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.config.SearchProperties;
import com.lqx.ragmcp.embedding.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    private EmbeddingService embeddingService;
    private SearchRepository repository;
    private SearchService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        repository = mock(SearchRepository.class);
        SearchProperties properties = new SearchProperties();
        service = new SearchService(embeddingService, repository, properties);
    }

    @Test
    void keywordOnlyDoesNotCallEmbeddingAndTruncatesLongContent() {
        SearchCandidate candidate = new SearchCandidate(
                UUID.randomUUID(), UUID.randomUUID(), "doc-key", "2025", "ACTIVE",
                LocalDate.of(2025, 1, 1), null, "标题", "3.2", "第三章", 12,
                "x".repeat(2500), "heading", 0, 0.4);
        when(repository.keywordSearch(any(), anyInt())).thenReturn(List.of(candidate));

        SearchResponse response = service.search(new SearchRequest(
                "关键词", 5, null, null, "2026-11-09", null, null, "keyword", true), TODAY);

        verify(embeddingService, never()).embed(anyList());
        assertThat(response.strategy()).isEqualTo("keyword");
        assertThat(response.chunkStrategy()).isEqualTo("heading");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).content()).hasSize(2001).endsWith("…");
        assertThat(response.results().get(0).score()).isCloseTo(1.0d / 61.0d,
                org.assertj.core.api.Assertions.within(1e-12));
        assertThat(response.conflicts()).isEmpty();
    }

    @Test
    void vectorRouteEmbeddingFailureIs503AndDoesNotQueryDatabase() {
        when(embeddingService.embed(anyList()))
                .thenThrow(ApiException.embeddingUnavailable("ollama down"));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
        verify(repository, never()).vectorSearch(any(), any(), anyInt());
    }

    @Test
    void vectorRouteTimeoutIs504() {
        when(embeddingService.embed(anyList()))
                .thenThrow(ApiException.embeddingTimeout("timeout"));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_TIMEOUT));
    }

    @Test
    void wrongEmbeddingDimensionIs503() {
        when(embeddingService.dimension()).thenReturn(1024);
        when(embeddingService.embed(anyList())).thenReturn(List.of(new float[512]));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE));
    }

    @Test
    void databaseFailureIsNotSwallowedAsEmptyResult() {
        when(embeddingService.dimension()).thenReturn(1024);
        when(embeddingService.embed(anyList())).thenReturn(List.of(unitVector()));
        when(repository.vectorSearch(any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.search(vectorRequest(), TODAY))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void vectorAndKeywordRoutesAreBothUsedForHybrid() {
        when(embeddingService.dimension()).thenReturn(1024);
        when(embeddingService.embed(anyList())).thenReturn(List.of(unitVector()));
        SearchCandidate a = RrfFusionTest.candidate(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000101", "heading", 0);
        SearchCandidate b = RrfFusionTest.candidate(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000102", "heading", 0);
        when(repository.vectorSearch(any(), any(), anyInt())).thenReturn(List.of(a));
        when(repository.keywordSearch(any(), anyInt())).thenReturn(List.of(b));

        SearchResponse response = service.search(new SearchRequest(
                "q", 5, null, null, "2026-11-09", null, null, "hybrid", null), TODAY);

        verify(repository).vectorSearch(any(), any(), anyInt());
        verify(repository).keywordSearch(any(), anyInt());
        assertThat(response.results()).hasSize(2);
    }

    private SearchRequest vectorRequest() {
        return new SearchRequest("q", 5, null, null, "2026-11-09",
                null, null, "vector", null);
    }

    private static float[] unitVector() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }
}
