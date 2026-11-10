package com.lqx.ragmcp.wiki;

import com.lqx.ragmcp.api.dto.PageCompileWikiResponse;
import com.lqx.ragmcp.api.dto.WikiCompileFailure;
import com.lqx.ragmcp.api.dto.WikiDocumentCompile;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.WikiProperties;
import com.lqx.ragmcp.document.DocumentRecord;
import com.lqx.ragmcp.document.DocumentRepository;
import com.lqx.ragmcp.embedding.EmbeddingService;
import com.lqx.ragmcp.page.PageAggregator;
import com.lqx.ragmcp.page.PageDraft;
import com.lqx.ragmcp.page.PageRepository;
import com.lqx.ragmcp.page.PageSourceChunk;
import com.lqx.ragmcp.parse.ClauseIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Phase 11: compile heading chunks into LLM wiki pages and persist them in {@code kb_page} with
 * {@code kind='wiki'}.
 *
 * <p><b>Fail closed.</b> Without {@code DEEPSEEK_API_KEY} the service throws
 * {@code ERR-LLM_NOT_CONFIGURED} before reading or writing {@code kb_page}; it never substitutes
 * synthetic content.</p>
 *
 * <p><b>Idempotency.</b> A document's previous wiki rows are deleted and the newly compiled set is
 * inserted in one transaction. Aggregate rows are untouched (the delete is scoped by kind), and a
 * run that produced zero pages does not delete the last good wiki version.</p>
 *
 * <p><b>Accounting.</b> Every section is classified as success, failed (transport/API) or skipped
 * (model output still invalid after the parse retry). Token usage includes retried calls.</p>
 */
@Service
public class WikiCompiler {

    private static final Logger log = LoggerFactory.getLogger(WikiCompiler.class);
    private static final int MAX_CONCURRENCY = 16;

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final EmbeddingService embeddingService;
    private final WikiSectionCompiler sectionCompiler;
    private final WikiProperties properties;
    private final TransactionTemplate transactionTemplate;

    public WikiCompiler(DocumentRepository documentRepository,
                        PageRepository pageRepository,
                        EmbeddingService embeddingService,
                        WikiSectionCompiler sectionCompiler,
                        WikiProperties properties,
                        TransactionTemplate transactionTemplate) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.embeddingService = embeddingService;
        this.sectionCompiler = sectionCompiler;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    public PageCompileWikiResponse compile(List<UUID> requestedDocIds, Integer requestedConcurrency) {
        if (!properties.isConfigured()) {
            throw ApiException.llmNotConfigured();
        }
        long startedAt = System.currentTimeMillis();
        int concurrency = normalizeConcurrency(requestedConcurrency);
        List<UUID> targets = requestedDocIds == null || requestedDocIds.isEmpty()
                ? pageRepository.allDocumentIds()
                : List.copyOf(new LinkedHashSet<>(requestedDocIds));

        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "wiki-compile");
            thread.setDaemon(true);
            return thread;
        });
        List<WikiDocumentCompile> documents = new ArrayList<>();
        List<WikiCompileFailure> failures = new ArrayList<>();
        long totalPromptTokens = 0L;
        long totalCompletionTokens = 0L;
        int totalSections = 0;
        int totalSuccess = 0;
        int totalFailed = 0;
        int totalSkipped = 0;
        try {
            for (UUID docId : targets) {
                try {
                    DocumentRecord document = documentRepository.findById(docId)
                            .orElseThrow(() -> ApiException.docNotFound(docId.toString()));
                    DocumentOutcome outcome = compileDocument(document, concurrency, pool);
                    totalSections += outcome.sectionCount();
                    totalSuccess += outcome.successCount();
                    totalFailed += outcome.failedCount();
                    totalSkipped += outcome.skippedCount();
                    totalPromptTokens += outcome.promptTokens();
                    totalCompletionTokens += outcome.completionTokens();
                    documents.add(outcome.summary());
                    failures.addAll(outcome.failures());
                    log.info("wiki compiled docId={} sections={} success={} failed={} skipped={}"
                                    + " pages={} tokens={} tookMs={}",
                            docId, outcome.sectionCount(), outcome.successCount(),
                            outcome.failedCount(), outcome.skippedCount(), outcome.pageCount(),
                            outcome.promptTokens() + outcome.completionTokens(), outcome.tookMs());
                } catch (RuntimeException ex) {
                    log.warn("wiki compile failed docId={} type={} message={}",
                            docId, ex.getClass().getSimpleName(), ex.getMessage());
                    failures.add(new WikiCompileFailure(docId.toString(), null, "document",
                            safeReason(ex)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        long tookMs = System.currentTimeMillis() - startedAt;
        return new PageCompileWikiResponse(
                properties.getModel(),
                documents.size(),
                totalSections,
                totalSuccess,
                totalFailed,
                totalSkipped,
                totalPromptTokens,
                totalCompletionTokens,
                totalPromptTokens + totalCompletionTokens,
                tookMs,
                List.copyOf(documents),
                List.copyOf(failures));
    }

    private DocumentOutcome compileDocument(DocumentRecord document, int concurrency,
                                            ExecutorService pool) {
        long startedAt = System.currentTimeMillis();
        List<PageSourceChunk> chunks = pageRepository.findHeadingChunks(document.id());
        List<WikiSection> sections = sections(document, chunks);
        List<String> failureReasons = new ArrayList<>();
        List<WikiCompileFailure> failures = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int skipped = 0;
        long promptTokens = 0L;
        long completionTokens = 0L;
        int llmCalls = 0;
        int pageCount = 0;

        List<WikiSectionCompiler.Outcome> outcomes = compileSections(sections, concurrency, pool);
        List<WikiSection> successfulSections = new ArrayList<>();
        List<WikiSectionCompiler.WikiPageContent> successfulPages = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            WikiSection section = sections.get(i);
            WikiSectionCompiler.Outcome outcome = outcomes.get(i);
            promptTokens += outcome.promptTokens();
            completionTokens += outcome.completionTokens();
            llmCalls += outcome.llmCalls();
            switch (outcome.status()) {
                case SUCCESS -> {
                    success++;
                    successfulSections.add(section);
                    successfulPages.add(outcome.page());
                }
                case SKIPPED -> {
                    skipped++;
                    failures.add(new WikiCompileFailure(document.id().toString(),
                            section.clausePath(), "parse", outcome.reason()));
                }
                case FAILED -> {
                    failed++;
                    failures.add(new WikiCompileFailure(document.id().toString(),
                            section.clausePath(), "llm", outcome.reason()));
                }
                default -> throw new IllegalStateException("unexpected outcome " + outcome.status());
            }
        }

        if (!successfulPages.isEmpty()) {
            try {
                List<PageRepository.PageInsert> inserts = buildInserts(document, successfulSections,
                        successfulPages);
                transactionTemplate.executeWithoutResult(status -> {
                    pageRepository.deleteByDocumentId(document.id(), PageRepository.KIND_WIKI);
                    pageRepository.insertPages(inserts);
                });
                pageCount = inserts.size();
            } catch (RuntimeException ex) {
                // The compiled pages never reached the database; report the whole batch as failed
                // instead of claiming pages that were rolled back.
                log.warn("wiki persist failed docId={} type={} message={} pages={}",
                        document.id(), ex.getClass().getSimpleName(), ex.getMessage(),
                        successfulPages.size());
                failed += success;
                success = 0;
                pageCount = 0;
                failures.add(new WikiCompileFailure(document.id().toString(), null, "embedding",
                        safeReason(ex)));
            }
        }

        long tookMs = System.currentTimeMillis() - startedAt;
        return new DocumentOutcome(
                new WikiDocumentCompile(
                        document.id().toString(), document.docKey(), document.version(),
                        document.title(), sections.size(), success, failed, skipped, pageCount,
                        llmCalls, promptTokens, completionTokens, tookMs),
                sections.size(), success, failed, skipped, pageCount,
                promptTokens, completionTokens, tookMs, failures);
    }

    /**
     * Runs the section compiles on the shared pool and preserves input order. Package-private so
     * the concurrency accounting can be unit-tested without a database.
     */
    List<WikiSectionCompiler.Outcome> compileSections(List<WikiSection> sections, int concurrency,
                                                      ExecutorService pool) {
        if (sections.isEmpty()) {
            return List.of();
        }
        List<Future<WikiSectionCompiler.Outcome>> futures = new ArrayList<>(sections.size());
        for (WikiSection section : sections) {
            futures.add(pool.submit(() -> sectionCompiler.compile(section)));
        }
        List<WikiSectionCompiler.Outcome> outcomes = new ArrayList<>(sections.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                outcomes.add(futures.get(i).get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                outcomes.add(WikiSectionCompiler.Outcome.failed("编译被中断", 0L, 0L, 0));
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                log.warn("wiki section task crashed section={} type={}",
                        sections.get(i).sectionLabel(), cause.getClass().getSimpleName());
                outcomes.add(WikiSectionCompiler.Outcome.failed(
                        "编译任务异常: " + cause.getClass().getSimpleName(), 0L, 0L, 0));
            }
        }
        return outcomes;
    }

    private List<PageRepository.PageInsert> buildInserts(
            DocumentRecord document,
            List<WikiSection> sections,
            List<WikiSectionCompiler.WikiPageContent> pages) {
        List<String> texts = pages.stream()
                .map(WikiSectionCompiler.WikiPageContent::embeddingText)
                .toList();
        List<float[]> vectors = embeddingService.embed(texts);
        if (vectors == null || vectors.size() != pages.size()) {
            throw ApiException.embeddingUnavailable("wiki 页面 embedding 返回数量与输入不一致");
        }
        List<PageRepository.PageInsert> inserts = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != embeddingService.dimension()) {
                throw ApiException.embeddingUnavailable("wiki 页面 embedding 维度不是 "
                        + embeddingService.dimension());
            }
            WikiSection section = sections.get(i);
            WikiSectionCompiler.WikiPageContent page = pages.get(i);
            PageDraft draft = new PageDraft(
                    section.pageKey(),
                    section.clausePath(),
                    section.leafClauseId(),
                    section.clauseIds(),
                    page.title(),
                    section.sectionIndex(),
                    1,
                    page.body(),
                    page.tokenCount(),
                    section.pageNo(),
                    section.pageEnd(),
                    // Every chunk of the section produced this page. The model-reported subset is
                    // kept inside the page JSON (`sources`), not used to shrink provenance.
                    section.chunks().stream().map(PageSourceChunk::chunkId).toList());
            inserts.add(new PageRepository.PageInsert(UUID.randomUUID(), document.id(),
                    PageRepository.KIND_WIKI, properties.getModel(), draft, vector));
        }
        return inserts;
    }

    /** Groups heading chunks by clause_path, exactly like the deterministic page layer. */
    static List<WikiSection> sections(DocumentRecord document, List<PageSourceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<PageSourceChunk> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparingInt(PageSourceChunk::chunkIndex));
        Map<String, List<PageSourceChunk>> groups = new LinkedHashMap<>();
        for (PageSourceChunk chunk : ordered) {
            String key = chunk.clausePath() == null || chunk.clausePath().isBlank()
                    ? PageAggregator.ROOT_KEY
                    : chunk.clausePath();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
        }
        String pageKeyPrefix = document.docKey() + "@"
                + (document.version() == null || document.version().isBlank()
                        ? "-" : document.version()) + "#";
        List<WikiSection> sections = new ArrayList<>(groups.size());
        int index = 0;
        for (Map.Entry<String, List<PageSourceChunk>> entry : groups.entrySet()) {
            boolean root = PageAggregator.ROOT_KEY.equals(entry.getKey());
            String clausePath = root ? null : entry.getKey();
            String leaf = root ? null : leafSegment(clausePath);
            String leafClauseId = root ? null : ClauseIds.extract(leaf);
            if (leafClauseId == null) {
                leafClauseId = firstClauseId(entry.getValue());
            }
            Set<String> clauseIds = new LinkedHashSet<>();
            Integer pageNo = null;
            Integer pageEnd = null;
            for (PageSourceChunk chunk : entry.getValue()) {
                if (chunk.clauseId() != null && !chunk.clauseId().isBlank()) {
                    clauseIds.add(chunk.clauseId());
                }
                if (chunk.pageNo() != null) {
                    pageNo = pageNo == null ? chunk.pageNo() : Math.min(pageNo, chunk.pageNo());
                }
                if (chunk.pageEnd() != null) {
                    pageEnd = pageEnd == null ? chunk.pageEnd() : Math.max(pageEnd, chunk.pageEnd());
                } else if (chunk.pageNo() != null) {
                    pageEnd = pageEnd == null ? chunk.pageNo() : Math.max(pageEnd, chunk.pageNo());
                }
            }
            sections.add(new WikiSection(
                    document.id(), document.docKey(), document.version(), document.title(),
                    pageKeyPrefix + entry.getKey(), clausePath, leafClauseId,
                    List.copyOf(clauseIds), index++, pageNo, pageEnd,
                    List.copyOf(entry.getValue())));
        }
        return sections;
    }

    private int normalizeConcurrency(Integer requested) {
        int value = requested == null ? properties.getConcurrency() : requested;
        return Math.max(1, Math.min(value, MAX_CONCURRENCY));
    }

    private static String firstClauseId(List<PageSourceChunk> chunks) {
        for (PageSourceChunk chunk : chunks) {
            if (chunk.clauseId() != null && !chunk.clauseId().isBlank()) {
                return chunk.clauseId();
            }
        }
        return null;
    }

    private static String leafSegment(String clausePath) {
        int separator = clausePath.lastIndexOf('>');
        return (separator >= 0 ? clausePath.substring(separator + 1) : clausePath).trim();
    }

    private static String safeReason(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        // Messages are produced by this service / ApiException and never contain credentials.
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record DocumentOutcome(
            WikiDocumentCompile summary,
            int sectionCount,
            int successCount,
            int failedCount,
            int skippedCount,
            int pageCount,
            long promptTokens,
            long completionTokens,
            long tookMs,
            List<WikiCompileFailure> failures) {
    }
}
