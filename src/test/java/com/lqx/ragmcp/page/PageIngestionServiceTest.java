package com.lqx.ragmcp.page;

import com.lqx.ragmcp.chunk.TokenCounter;
import com.lqx.ragmcp.config.PageProperties;
import com.lqx.ragmcp.document.DocumentRecord;
import com.lqx.ragmcp.document.DocumentRepository;
import com.lqx.ragmcp.embedding.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Upload hot-path contract: page ingestion is best effort and must never propagate a failure.
 */
class PageIngestionServiceTest {

    private DocumentRepository documentRepository;
    private PageRepository pageRepository;
    private EmbeddingService embeddingService;
    private PageProperties properties;
    private PageIngestionService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        pageRepository = mock(PageRepository.class);
        embeddingService = mock(EmbeddingService.class);
        properties = new PageProperties();
        service = new PageIngestionService(documentRepository, pageRepository,
                new PageAggregator(new TokenCounter()), embeddingService, properties,
                mock(TransactionTemplate.class));
    }

    @Test
    void ingestAfterUploadSwallowsAggregationFailures() {
        when(documentRepository.findById(any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> service.ingestAfterUpload(UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void ingestAfterUploadSwallowsEmbeddingFailures() {
        DocumentRecord document = document();
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(pageRepository.findHeadingChunks(document.id())).thenReturn(List.of(
                new PageSourceChunk(UUID.randomUUID(), 0, "正文", 10, "1.1",
                        "1 > 1.1 小节", 1, 1)));
        when(embeddingService.embed(any())).thenThrow(new IllegalStateException("ollama down"));

        assertThatCode(() -> service.ingestAfterUpload(document.id()))
                .doesNotThrowAnyException();
        verify(pageRepository, never()).deleteByDocumentId(any());
        verify(pageRepository, never()).insertPages(any());
    }

    @Test
    void ingestOnUploadDisabledSkipsEverything() {
        properties.setIngestOnUpload(false);

        service.ingestAfterUpload(UUID.randomUUID());

        verifyNoInteractions(documentRepository, pageRepository, embeddingService);
    }

    private static DocumentRecord document() {
        return new DocumentRecord(UUID.randomUUID(), "doc-key", "标题", "MD", "upload://a.md",
                "0".repeat(64), "2025", "bge-m3", LocalDate.of(2025, 1, 1), null,
                "ACTIVE", null, null, null, null, "{}", OffsetDateTime.now(), null);
    }
}
