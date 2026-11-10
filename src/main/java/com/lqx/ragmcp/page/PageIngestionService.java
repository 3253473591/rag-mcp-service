package com.lqx.ragmcp.page;

import com.lqx.ragmcp.api.dto.PageDocumentRebuild;
import com.lqx.ragmcp.api.dto.PageRebuildFailure;
import com.lqx.ragmcp.api.dto.PageRebuildResponse;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.PageProperties;
import com.lqx.ragmcp.document.DocumentRecord;
import com.lqx.ragmcp.document.DocumentRepository;
import com.lqx.ragmcp.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Builds {@code kb_page} from heading chunks.
 *
 * <p><b>Idempotency.</b> Rebuilding a document deletes every existing page of that document and
 * inserts the freshly aggregated set in one transaction, so running it twice produces the same
 * rows (and the same count) instead of duplicates or orphans.</p>
 *
 * <p><b>Upload is a hot path.</b> {@link #ingestAfterUpload(UUID)} is best effort: page
 * aggregation runs synchronously after the document transaction commits, and any runtime failure
 * is logged and swallowed. A page-layer problem must never turn a successful upload into an error;
 * the repair path is {@code POST /api/v1/admin/pages/rebuild}, and the failure is visible in the
 * application log (document id + exception class, no SQL/stack in the client response).</p>
 */
@Service
public class PageIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PageIngestionService.class);

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final PageAggregator aggregator;
    private final EmbeddingService embeddingService;
    private final PageProperties properties;
    private final TransactionTemplate transactionTemplate;

    public PageIngestionService(DocumentRepository documentRepository,
                                PageRepository pageRepository,
                                PageAggregator aggregator,
                                EmbeddingService embeddingService,
                                PageProperties properties,
                                TransactionTemplate transactionTemplate) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.aggregator = aggregator;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Best-effort page ingestion for a freshly uploaded document. Never throws.
     */
    public void ingestAfterUpload(UUID documentId) {
        if (!properties.isIngestOnUpload()) {
            log.debug("page ingestion disabled app.pages.ingest-on-upload=false docId={}", documentId);
            return;
        }
        try {
            DocumentRebuild result = rebuildDocument(documentId);
            log.info("pages ingested after upload docId={} chunks={} pageGroups={} pages={}",
                    documentId, result.chunkCount(), result.pageGroupCount(), result.pageCount());
        } catch (RuntimeException ex) {
            // Deliberate: the upload already committed. Page aggregation is a secondary index whose
            // repair path is POST /api/v1/admin/pages/rebuild.
            log.error("page ingestion failed after upload docId={} type={} message={}",
                    documentId, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /**
     * Rebuild one document. Throws {@code ERR-DOC_NOT_FOUND} when the id does not exist; other
     * failures (embedding/DB) propagate so the caller can report them.
     */
    public DocumentRebuild rebuildDocument(UUID documentId) {
        DocumentRecord document = documentRepository.findById(documentId)
                .orElseThrow(() -> ApiException.docNotFound(documentId.toString()));
        List<PageSourceChunk> chunks = pageRepository.findHeadingChunks(documentId);
        List<PageDraft> drafts = aggregator.aggregate(
                document.docKey(), document.version(), document.title(), chunks,
                properties.getMaxTokens());

        List<PageRepository.PageInsert> inserts = embedPages(documentId, drafts);
        transactionTemplate.executeWithoutResult(status -> {
            pageRepository.deleteByDocumentId(documentId);
            pageRepository.insertPages(inserts);
        });
        long totalTokens = drafts.stream().mapToLong(PageDraft::tokenCount).sum();
        double avgTokens = drafts.isEmpty() ? 0.0d
                : Math.round(totalTokens * 10.0d / drafts.size()) / 10.0d;
        long pageGroups = drafts.stream().map(PageDraft::pageKey).distinct().count();
        return new DocumentRebuild(documentId, document.docKey(), document.version(),
                document.title(), chunks.size(), (int) pageGroups, drafts.size(), totalTokens,
                avgTokens);
    }

    /**
     * Rebuild every requested document (all documents when {@code docIds} is empty). A failure on
     * one document does not abort the others; failures are reported in the response.
     */
    public PageRebuildResponse rebuild(List<UUID> docIds) {
        long startedAt = System.currentTimeMillis();
        List<UUID> targets;
        if (docIds == null || docIds.isEmpty()) {
            targets = pageRepository.allDocumentIds();
        } else {
            targets = List.copyOf(new LinkedHashSet<>(docIds));
        }
        List<PageDocumentRebuild> rebuilt = new ArrayList<>(targets.size());
        List<PageRebuildFailure> failures = new ArrayList<>();
        int totalPages = 0;
        int totalChunks = 0;
        for (UUID docId : targets) {
            try {
                DocumentRebuild result = rebuildDocument(docId);
                rebuilt.add(new PageDocumentRebuild(
                        result.documentId().toString(),
                        result.docKey(),
                        result.version(),
                        result.docTitle(),
                        result.chunkCount(),
                        result.pageGroupCount(),
                        result.pageCount(),
                        result.totalTokens(),
                        result.avgTokens()));
                totalPages += result.pageCount();
                totalChunks += result.chunkCount();
            } catch (RuntimeException ex) {
                log.warn("page rebuild failed docId={} type={} message={}",
                        docId, ex.getClass().getSimpleName(), ex.getMessage());
                failures.add(new PageRebuildFailure(docId.toString(),
                        ex.getClass().getSimpleName(), ex.getMessage()));
            }
        }
        long tookMs = System.currentTimeMillis() - startedAt;
        log.info("pages rebuilt documents={} failed={} pages={} chunks={} tookMs={}",
                rebuilt.size(), failures.size(), totalPages, totalChunks, tookMs);
        return new PageRebuildResponse(rebuilt.size(), totalPages, totalChunks,
                tookMs, List.copyOf(rebuilt), List.copyOf(failures));
    }

    public List<PageRepository.DocumentPageStats> stats() {
        return pageRepository.stats();
    }

    private List<PageRepository.PageInsert> embedPages(UUID documentId, List<PageDraft> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        List<String> texts = drafts.stream().map(PageDraft::embeddingText).toList();
        List<float[]> vectors = embeddingService.embed(texts);
        if (vectors == null || vectors.size() != drafts.size()) {
            throw ApiException.embeddingUnavailable("页面 embedding 返回数量与输入不一致");
        }
        List<PageRepository.PageInsert> inserts = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != embeddingService.dimension()) {
                throw ApiException.embeddingUnavailable("页面 embedding 维度不是 "
                        + embeddingService.dimension());
            }
            inserts.add(new PageRepository.PageInsert(
                    UUID.randomUUID(), documentId, drafts.get(i), vector));
        }
        return inserts;
    }

    /** Outcome of rebuilding a single document. */
    public record DocumentRebuild(
            UUID documentId,
            String docKey,
            String version,
            String docTitle,
            int chunkCount,
            int pageGroupCount,
            int pageCount,
            long totalTokens,
            double avgTokens) {
    }
}
