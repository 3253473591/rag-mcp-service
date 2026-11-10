package com.lqx.ragmcp.wiki;

import com.lqx.ragmcp.api.dto.PageCompileWikiResponse;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.WikiProperties;
import com.lqx.ragmcp.document.DocumentRecord;
import com.lqx.ragmcp.document.DocumentRepository;
import com.lqx.ragmcp.embedding.EmbeddingService;
import com.lqx.ragmcp.page.PageRepository;
import com.lqx.ragmcp.page.PageSourceChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 11 compile accounting: concurrency is bounded, success/failed/skipped add up, only the
 * wiki kind is replaced (idempotently), and a missing key fails closed before any DB/LLM work.
 */
class WikiCompilerTest {

    private static final String MODEL = "deepseek-flash";

    private DocumentRepository documentRepository;
    private PageRepository pageRepository;
    private EmbeddingService embeddingService;
    private WikiSectionCompiler sectionCompiler;
    private TransactionTemplate transactionTemplate;
    private WikiProperties properties;
    private DocumentRecord document;
    private UUID docId;
    private WikiCompiler compiler;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        pageRepository = mock(PageRepository.class);
        embeddingService = mock(EmbeddingService.class);
        sectionCompiler = mock(WikiSectionCompiler.class);
        transactionTemplate = mock(TransactionTemplate.class);
        properties = new WikiProperties();
        properties.setApiKey("fake-key");
        properties.setModel(MODEL);
        properties.setConcurrency(2);
        docId = UUID.randomUUID();
        document = document(docId);
        compiler = new WikiCompiler(documentRepository, pageRepository, embeddingService,
                sectionCompiler, properties, transactionTemplate);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void compileCountsSectionsConcurrentlyAndWritesWikiRows() {
        stubThreeSections();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(sectionCompiler.compile(any(WikiSection.class))).thenAnswer(invocation -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40L);
            } finally {
                active.decrementAndGet();
            }
            return successOutcome("页面");
        });
        stubEmbedding();

        PageCompileWikiResponse response = compiler.compile(List.of(docId), 2);

        assertThat(response.documentCount()).isEqualTo(1);
        assertThat(response.sectionCount()).isEqualTo(3);
        assertThat(response.successCount()).isEqualTo(3);
        assertThat(response.failedCount()).isZero();
        assertThat(response.skippedCount()).isZero();
        assertThat(response.documents().get(0).pageCount()).isEqualTo(3);
        assertThat(response.promptTokens()).isEqualTo(30L);
        assertThat(response.completionTokens()).isEqualTo(15L);
        assertThat(response.totalTokens()).isEqualTo(45L);
        assertThat(maxActive.get()).isBetween(2, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PageRepository.PageInsert>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(pageRepository).deleteByDocumentId(docId, PageRepository.KIND_WIKI);
        verify(pageRepository).insertPages(captor.capture());
        assertThat(captor.getValue()).hasSize(3)
                .allSatisfy(insert -> {
                    assertThat(insert.kind()).isEqualTo(PageRepository.KIND_WIKI);
                    assertThat(insert.llmModel()).isEqualTo(MODEL);
                });
    }

    @Test
    void successFailedAndSkippedAreCountedSeparately() {
        stubThreeSections();
        when(sectionCompiler.compile(any(WikiSection.class)))
                .thenReturn(successOutcome("成功页"))
                .thenReturn(WikiSectionCompiler.Outcome.skipped("坏 JSON", 5L, 2L, 2))
                .thenReturn(WikiSectionCompiler.Outcome.failed("HTTP 500", 3L, 1L, 1));
        stubEmbedding();

        PageCompileWikiResponse response = compiler.compile(List.of(docId), 2);

        assertThat(response.sectionCount()).isEqualTo(3);
        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.documents().get(0).pageCount()).isEqualTo(1);
        assertThat(response.failures()).hasSize(2);
        assertThat(response.failures()).anySatisfy(failure -> {
            assertThat(failure.stage()).isEqualTo("parse");
            assertThat(failure.reason()).isEqualTo("坏 JSON");
        }).anySatisfy(failure -> {
            assertThat(failure.stage()).isEqualTo("llm");
            assertThat(failure.reason()).isEqualTo("HTTP 500");
        });
        assertThat(response.totalTokens()).isEqualTo(26L);
        verify(pageRepository).insertPages(any());
    }

    @Test
    void repeatedCompileIsIdempotentForTheWikiKind() {
        stubThreeSections();
        when(sectionCompiler.compile(any(WikiSection.class))).thenAnswer(i -> successOutcome("页面"));
        stubEmbedding();

        compiler.compile(List.of(docId), 2);
        compiler.compile(List.of(docId), 2);

        verify(pageRepository, times(2)).deleteByDocumentId(docId, PageRepository.KIND_WIKI);
        verify(pageRepository, times(2)).insertPages(any());
    }

    @Test
    void missingKeyFailsClosedBeforeAnyWork() {
        WikiProperties unconfigured = new WikiProperties();
        unconfigured.setApiKey("");
        WikiCompiler failing = new WikiCompiler(documentRepository, pageRepository,
                embeddingService, sectionCompiler, unconfigured, transactionTemplate);

        assertThatThrownBy(() -> failing.compile(List.of(docId), 2))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode().code())
                        .isEqualTo("ERR-LLM_NOT_CONFIGURED"));
        verifyNoInteractions(documentRepository, pageRepository, embeddingService, sectionCompiler);
    }

    @Test
    void embeddingFailureReportsTheCompiledBatchAsFailedAndWritesNothing() {
        stubThreeSections();
        when(sectionCompiler.compile(any(WikiSection.class))).thenAnswer(i -> successOutcome("页面"));
        when(embeddingService.embed(anyList()))
                .thenThrow(ApiException.embeddingUnavailable("ollama down"));

        PageCompileWikiResponse response = compiler.compile(List.of(docId), 2);

        assertThat(response.sectionCount()).isEqualTo(3);
        assertThat(response.successCount()).isZero();
        assertThat(response.failedCount()).isEqualTo(3);
        assertThat(response.documents().get(0).pageCount()).isZero();
        assertThat(response.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.stage()).isEqualTo("embedding");
            assertThat(failure.reason()).contains("ollama down");
        });
        verify(pageRepository, never()).deleteByDocumentId(any(), any());
        verify(pageRepository, never()).insertPages(any());
    }

    @Test
    void sectionsAreGroupedByClausePathInChunkOrder() {
        List<PageSourceChunk> chunks = List.of(
                chunk(0, "1.1 年假", "年假正文"),
                chunk(1, null, "前言"),
                chunk(2, "1.2 病假", "病假正文"),
                chunk(3, "1.1 年假", "年假第二段"));

        List<WikiSection> sections = WikiCompiler.sections(document, chunks);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).pageKey()).isEqualTo("handbook@2025#1.1 年假");
        assertThat(sections.get(0).leafClauseId()).isEqualTo("1.1");
        assertThat(sections.get(0).chunks()).hasSize(2);
        assertThat(sections.get(1).clausePath()).isNull();
        assertThat(sections.get(1).pageKey()).isEqualTo("handbook@2025#__root__");
        assertThat(sections.get(1).leafClauseId()).isNull();
        assertThat(sections.get(2).clausePath()).isEqualTo("1.2 病假");
        assertThat(sections.get(2).sectionIndex()).isEqualTo(2);
    }

    @Test
    void compileSectionsBoundedConcurrencyHelper() {
        WikiSection section = new WikiSection(docId, "handbook", "2025", "员工手册",
                "handbook@2025#1", "1", "1", List.of("1"), 0, 1, 1,
                List.of(chunk(0, "1", "正文")));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(sectionCompiler.compile(any(WikiSection.class))).thenAnswer(i -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30L);
            } finally {
                active.decrementAndGet();
            }
            return successOutcome("页");
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var outcomes = compiler.compileSections(List.of(section, section, section, section),
                    2, pool);
            assertThat(outcomes).hasSize(4).allMatch(o -> o.status() == WikiSectionCompiler.Status.SUCCESS);
            assertThat(maxActive.get()).isBetween(2, 2);
        } finally {
            pool.shutdownNow();
        }
    }

    private void stubThreeSections() {
        when(pageRepository.allDocumentIds()).thenReturn(List.of(docId));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(pageRepository.findHeadingChunks(docId)).thenReturn(List.of(
                chunk(0, "1.1 年假", "年假正文"),
                chunk(1, "1.2 病假", "病假正文"),
                chunk(2, null, "前言")));
    }

    private void stubEmbedding() {
        when(embeddingService.dimension()).thenReturn(1024);
        when(embeddingService.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new java.util.ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = new float[1024];
                vector[0] = 1.0f;
                vectors.add(vector);
            }
            return vectors;
        });
    }

    private static WikiSectionCompiler.Outcome successOutcome(String title) {
        WikiSectionCompiler.WikiPageContent page = new WikiSectionCompiler.WikiPageContent(
                title, "摘要", List.of("要点"), List.of(), List.of(), "{\"title\":\"" + title + "\"}",
                10);
        return WikiSectionCompiler.Outcome.success(page, 10L, 5L, 1);
    }

    private static PageSourceChunk chunk(int index, String clausePath, String content) {
        String clauseId = clausePath == null ? null : clausePath.split("\\s+")[0];
        return new PageSourceChunk(UUID.randomUUID(), index, content, 10, clauseId,
                clausePath, 1, 1);
    }

    private static DocumentRecord document(UUID id) {
        return new DocumentRecord(id, "handbook", "员工手册", "MD", "upload://handbook.md",
                "0".repeat(64), "2025", "bge-m3", LocalDate.of(2025, 1, 1), null,
                "ACTIVE", null, null, null, null, "{}", OffsetDateTime.now(), null);
    }
}
