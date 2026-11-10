package com.lqx.ragmcp.api;

import com.lqx.ragmcp.api.dto.PageDocumentStats;
import com.lqx.ragmcp.api.dto.PageRebuildRequest;
import com.lqx.ragmcp.api.dto.PageRebuildResponse;
import com.lqx.ragmcp.api.dto.PageStatsResponse;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.governance.AdminAuth;
import com.lqx.ragmcp.page.PageIngestionService;
import com.lqx.ragmcp.page.PageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Administrative page-layer endpoints (Phase 10). Both require {@code X-Admin-Token} and reuse
 * {@link AdminAuth}; an unset admin token fails closed with 401 like the governance endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/pages")
public class PageAdminController {

    private final PageIngestionService pageIngestionService;
    private final AdminAuth adminAuth;

    public PageAdminController(PageIngestionService pageIngestionService, AdminAuth adminAuth) {
        this.pageIngestionService = pageIngestionService;
        this.adminAuth = adminAuth;
    }

    /**
     * Rebuild pages for all documents (or the requested {@code docIds}). Idempotent: each
     * document's old pages are deleted and re-inserted in one transaction, so repeated calls
     * converge to the same rows.
     */
    @PostMapping("/rebuild")
    public PageRebuildResponse rebuild(
            @RequestHeader(value = AdminAuth.HEADER, required = false) String adminToken,
            @RequestBody(required = false) PageRebuildRequest request) {
        adminAuth.requireAuthorized(adminToken);
        return pageIngestionService.rebuild(parseDocIds(request));
    }

    /** Read-only page statistics used by the Phase 10 acceptance report. */
    @GetMapping("/stats")
    public PageStatsResponse stats(
            @RequestHeader(value = AdminAuth.HEADER, required = false) String adminToken) {
        adminAuth.requireAuthorized(adminToken);
        List<PageRepository.DocumentPageStats> rows = pageIngestionService.stats();
        List<PageDocumentStats> documents = new ArrayList<>(rows.size());
        int pageCount = 0;
        long totalTokens = 0;
        for (PageRepository.DocumentPageStats row : rows) {
            pageCount += row.pageCount();
            totalTokens += row.totalTokens();
            documents.add(new PageDocumentStats(
                    row.documentId().toString(),
                    row.docKey(),
                    row.docTitle(),
                    row.version(),
                    row.status(),
                    row.pageCount(),
                    row.pageGroups(),
                    row.totalTokens(),
                    row.avgTokens(),
                    row.maxTokens()));
        }
        double avgTokens = pageCount == 0 ? 0.0d
                : Math.round(totalTokens * 10.0d / pageCount) / 10.0d;
        return new PageStatsResponse(rows.size(), pageCount, totalTokens, avgTokens, documents);
    }

    private static List<UUID> parseDocIds(PageRebuildRequest request) {
        if (request == null || request.docIds() == null || request.docIds().isEmpty()) {
            return List.of();
        }
        List<UUID> docIds = new ArrayList<>(request.docIds().size());
        for (String raw : request.docIds()) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.validationField("docIds", "docIds 必须是合法 UUID");
            }
            try {
                docIds.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ex) {
                throw ApiException.validationField("docIds", "docIds 必须是合法 UUID");
            }
        }
        return docIds;
    }
}
