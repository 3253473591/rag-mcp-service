package com.lqx.ragmcp.governance;

import com.lqx.ragmcp.api.error.ApiException;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure governance rules shared by the Phase 4 approval API and (later) Phase 5 mandate
 * enforcement. No Spring/JDBC dependency, so the narrowing rules are unit-testable in isolation.
 *
 * <p>Approval is monotonic: the administrator may keep or shrink the requested tools, documents
 * and topK, never expand them. {@code requestedDocIds == null} means "all documents", while the
 * empty list means "no documents"; the same convention applies to {@code allowedDocIds}.</p>
 */
public final class MandatePolicy {

    public static final Set<String> V1_TOOLS = Set.of("search_knowledge", "get_document");

    public static final int MIN_TTL_SECONDS = 60;
    public static final int MAX_TTL_SECONDS = 86_400;
    public static final int DEFAULT_TTL_SECONDS = 3_600;

    public static final int MIN_TOPK = 1;
    public static final int MAX_TOPK = 20;
    public static final int DEFAULT_TOPK = 5;

    public static final int MAX_TOOLS = 5;
    public static final int MAX_DOC_IDS = 50;

    private MandatePolicy() {
    }

    /**
     * Validates an approve body against the WorkItem's requested scope and computes the mandate
     * fields. Throws {@code ERR-VALIDATION} when the administrator tries to expand the scope or
     * when the explicit-all semantics are ambiguous.
     */
    public static ApprovalDecision decideApproval(WorkItemRecord workItem,
                                                  ApprovalRequest request,
                                                  OffsetDateTime now) {
        int ttlSeconds = resolveTtl(request.ttlSeconds());
        List<String> allowedTools = resolveAllowedTools(workItem, request.allowedTools());
        List<UUID> allowedDocIds = resolveAllowedDocIds(workItem, request);
        int maxTopK = resolveMaxTopK(workItem, request.maxTopK());

        return new ApprovalDecision(
                allowedTools,
                allowedDocIds,
                maxTopK,
                ttlSeconds,
                now.plusSeconds(ttlSeconds),
                normalizeReason(request.reason()));
    }

    private static int resolveTtl(Integer ttlSeconds) {
        int value = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        if (value < MIN_TTL_SECONDS || value > MAX_TTL_SECONDS) {
            throw ApiException.validationField("ttlSeconds",
                    "ttlSeconds 必须在 60~86400 之间");
        }
        return value;
    }

    private static List<String> resolveAllowedTools(WorkItemRecord workItem, List<String> rawAllowedTools) {
        if (rawAllowedTools == null) {
            return workItem.requestedTools();
        }
        if (rawAllowedTools.size() > MAX_TOOLS) {
            throw ApiException.validationField("allowedTools", "allowedTools 最多 5 个");
        }
        Set<String> tools = new LinkedHashSet<>();
        for (String raw : rawAllowedTools) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.validationField("allowedTools",
                        "allowedTools 只能包含 search_knowledge / get_document");
            }
            String tool = raw.trim();
            if (!V1_TOOLS.contains(tool)) {
                throw ApiException.validationField("allowedTools",
                        "allowedTools 只能包含 search_knowledge / get_document");
            }
            tools.add(tool);
        }
        if (!new LinkedHashSet<>(workItem.requestedTools()).containsAll(tools)) {
            throw ApiException.validationField("allowedTools",
                    "allowedTools 必须是 requestedTools 的子集，不能扩大申请范围");
        }
        return List.copyOf(tools);
    }

    private static List<UUID> resolveAllowedDocIds(WorkItemRecord workItem, ApprovalRequest request) {
        List<UUID> requestedDocIds = workItem.requestedDocIds();

        if (!request.allowedDocIdsProvided()) {
            if (requestedDocIds == null) {
                // The request asked for all documents, so "all" must be an explicit admin choice.
                throw ApiException.validationField("allowedDocIds",
                        "申请范围为全部文档时必须显式提供 allowedDocIds（null=全部，[]=无）");
            }
            return requestedDocIds;
        }

        if (request.allowedDocIds() == null) {
            if (requestedDocIds != null) {
                throw ApiException.validationField("allowedDocIds",
                        "申请范围不是全部文档时 allowedDocIds 不能为 null");
            }
            return null;
        }

        List<UUID> allowedDocIds = parseDocIds(request.allowedDocIds());
        if (requestedDocIds != null
                && !new LinkedHashSet<>(requestedDocIds).containsAll(allowedDocIds)) {
            throw ApiException.validationField("allowedDocIds",
                    "allowedDocIds 必须是 requestedDocIds 的子集，不能扩大申请范围");
        }
        return allowedDocIds;
    }

    private static int resolveMaxTopK(WorkItemRecord workItem, Integer rawMaxTopK) {
        int maxTopK = rawMaxTopK == null ? workItem.requestedTopK() : rawMaxTopK;
        if (maxTopK < MIN_TOPK || maxTopK > MAX_TOPK) {
            throw ApiException.validationField("maxTopK", "maxTopK 必须在 1~20 之间");
        }
        if (maxTopK > workItem.requestedTopK()) {
            throw ApiException.validationField("maxTopK",
                    "maxTopK 不能大于 requestedTopK，不能扩大申请范围");
        }
        return maxTopK;
    }

    private static List<UUID> parseDocIds(List<String> rawDocIds) {
        if (rawDocIds.size() > MAX_DOC_IDS) {
            throw ApiException.validationField("allowedDocIds", "allowedDocIds 最多 50 个");
        }
        Set<UUID> docIds = new LinkedHashSet<>();
        for (String raw : rawDocIds) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.validationField("allowedDocIds",
                        "allowedDocIds 必须都是合法 UUID");
            }
            try {
                docIds.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ex) {
                throw ApiException.validationField("allowedDocIds",
                        "allowedDocIds 必须都是合法 UUID");
            }
        }
        return List.copyOf(docIds);
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---------------------------------------------------------------------------------------
    // Phase 5 read-only checks. Not wired into MCP in Phase 4; kept here so the mandate rules
    // have one implementation and unit tests before the MCP layer consumes them.
    // ---------------------------------------------------------------------------------------

    /** ACTIVE and not yet expired (docs/08-governance.md 5.2). */
    public static boolean isActive(MandateRecord mandate, OffsetDateTime now) {
        return mandate != null
                && MandateRecord.STATUS_ACTIVE.equals(mandate.status())
                && mandate.expiresAt() != null
                && now.isBefore(mandate.expiresAt());
    }

    public static boolean allowsTool(MandateRecord mandate, String tool) {
        return mandate != null && tool != null && mandate.allowedTools().contains(tool);
    }

    public static boolean allowsTopK(MandateRecord mandate, int topK) {
        return mandate != null
                && topK >= MIN_TOPK
                && topK <= mandate.maxTopK();
    }

    /**
     * True when the mandate's document scope does not exclude the explicitly requested ids.
     * A null/empty request means "no docIds filter"; Phase 5 must still push
     * {@code allowedDocIds} into SQL, so it is not a scope violation on its own. A non-empty
     * request must be a subset of a restricted mandate.
     */
    public static boolean allowsDocIds(MandateRecord mandate, List<UUID> requestedDocIds) {
        if (mandate == null) {
            return false;
        }
        if (mandate.allowedDocIds() == null) {
            return true;
        }
        if (requestedDocIds == null || requestedDocIds.isEmpty()) {
            return true;
        }
        return new LinkedHashSet<>(mandate.allowedDocIds()).containsAll(requestedDocIds);
    }
}
