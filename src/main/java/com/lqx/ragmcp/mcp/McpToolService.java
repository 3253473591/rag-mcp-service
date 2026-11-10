package com.lqx.ragmcp.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.api.dto.AbstainDiagnostics;
import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.api.dto.SearchResultItem;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import com.lqx.ragmcp.document.DocumentDetail;
import com.lqx.ragmcp.document.DocumentRecord;
import com.lqx.ragmcp.document.DocumentService;
import com.lqx.ragmcp.governance.MandatePolicy;
import com.lqx.ragmcp.governance.MandateRecord;
import com.lqx.ragmcp.governance.WorkItemRepository;
import com.lqx.ragmcp.search.AbstainReason;
import com.lqx.ragmcp.search.AnswerFallback;
import com.lqx.ragmcp.search.SearchRequestValidator;
import com.lqx.ragmcp.search.SearchService;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5 MCP tool business layer: mandate enforcement, retrieval/document reads and audit.
 *
 * <p>Validation order follows docs/08-governance.md section 5: existence -> ACTIVE/expiry ->
 * allowed tool -> topK -> docIds subset -> document scope -> execute. Business failures are
 * returned as {@code CallToolResult(isError=true)} with a stable {@code ERR-*} code; they are
 * never HTTP 403. Only malformed arguments use JSON-RPC {@code -32602}, and unexpected failures
 * use {@code -32603} without leaking a stack trace.</p>
 *
 * <p>When {@code docIds} is absent, the mandate's {@code allowedDocIds} is pushed into the
 * retrieval request, so the SQL layer always filters. {@code allowedDocIds == null} means all
 * documents; {@code []} means no documents and yields an empty result without leaking anything.</p>
 */
@Service
public class McpToolService {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);

    public static final String TOOL_SEARCH = "search_knowledge";
    public static final String TOOL_DOCUMENT = "get_document";

    /** docs/02-api-contract.md 1.2: MCP returns at most 10 results. */
    public static final int MAX_MCP_RESULTS = 10;

    private static final int JSONRPC_INVALID_PARAMS = -32602;
    private static final int JSONRPC_INTERNAL_ERROR = -32603;

    private static final String ERR_MANDATE_REQUIRED = "ERR-MANDATE_REQUIRED";
    private static final String ERR_MANDATE_EXPIRED = "ERR-MANDATE_EXPIRED";
    private static final String ERR_MANDATE_SCOPE = "ERR-MANDATE_SCOPE";

    private final WorkItemRepository workItemRepository;
    private final SearchService searchService;
    private final DocumentService documentService;
    private final McpAuditService auditService;
    private final ObjectMapper objectMapper;
    private final AnswerFallback answerFallback;

    public McpToolService(WorkItemRepository workItemRepository,
                          SearchService searchService,
                          DocumentService documentService,
                          McpAuditService auditService,
                          ObjectMapper objectMapper,
                          AnswerFallback answerFallback) {
        this.workItemRepository = workItemRepository;
        this.searchService = searchService;
        this.documentService = documentService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.answerFallback = answerFallback;
    }

    public McpSchema.CallToolResult searchKnowledge(Map<String, Object> arguments) {
        return execute(TOOL_SEARCH, arguments, context -> doSearch(arguments, context));
    }

    public McpSchema.CallToolResult getDocument(Map<String, Object> arguments) {
        return execute(TOOL_DOCUMENT, arguments, context -> doGetDocument(arguments, context));
    }

    // ---------------------------------------------------------------------------------------
    // search_knowledge
    // ---------------------------------------------------------------------------------------

    private McpSchema.CallToolResult doSearch(Map<String, Object> arguments, AuditContext context) {
        String mandateIdRaw = optionalString(arguments, "mandateId");
        if (mandateIdRaw == null || mandateIdRaw.isBlank()) {
            throw new McpToolError(ERR_MANDATE_REQUIRED, "缺少 mandateId，MCP 调用默认拒绝");
        }
        UUID mandateId = parseUuid("mandateId", mandateIdRaw.trim());
        context.mandateId = mandateId;

        MandateRecord mandate = requireMandate(mandateId, TOOL_SEARCH, context);

        int topK = optionalInt(arguments, "topK", MandatePolicy.DEFAULT_TOPK, 1, 20);
        if (!MandatePolicy.allowsTopK(mandate, topK)) {
            throw new McpToolError(ERR_MANDATE_SCOPE,
                    "topK 超过 mandate 允许的 maxTopK=" + mandate.maxTopK());
        }

        List<UUID> requestedDocIds = optionalDocIds(arguments, "docIds");
        List<UUID> effectiveDocIds = enforceDocScope(mandate, requestedDocIds);

        String query = optionalString(arguments, "query");
        if (query == null || query.isBlank() || query.length() > SearchRequestValidator.MAX_QUERY_CHARS) {
            throw invalidParams("query 必须是 1~500 字符的字符串");
        }
        String version = optionalString(arguments, "version");
        if (version != null && version.isBlank()) {
            version = null;
        }
        if (version != null && version.length() > SearchRequestValidator.MAX_VERSION_CHARS) {
            throw invalidParams("version 不能超过 50 字符");
        }
        String asOf = optionalString(arguments, "asOf");
        if (asOf != null) {
            validateAsOf(asOf);
        }
        boolean includeSuperseded = optionalBoolean(arguments, "includeSuperseded", false);
        boolean detectConflicts = optionalBoolean(arguments, "detectConflicts", false);
        String chunkStrategy = optionalString(arguments, "chunkStrategy");
        ChunkStrategy chunkStrategyValue = chunkStrategy == null
                ? ChunkStrategy.HEADING
                : ChunkStrategy.from(chunkStrategy).orElseThrow(
                        () -> invalidParams("chunkStrategy 只允许 fixed / heading"));

        // allowedDocIds == [] means "no document": do not call retrieval at all. Returning an
        // empty result is the least surprising shape and cannot leak any document. It also
        // satisfies the Phase 7 contract: empty results always carry an honest message.
        if (mandate.allowedDocIds() != null && mandate.allowedDocIds().isEmpty()) {
            return successResult(searchBody(List.of(), true, AbstainReason.NO_MATCH.name(),
                    answerFallback.fallback(null, AbstainReason.NO_MATCH),
                    AbstainDiagnostics.emptyNoMatch()));
        }

        SearchRequest request = new SearchRequest(
                query,
                topK,
                effectiveDocIds.stream().map(UUID::toString).toList(),
                version,
                asOf,
                includeSuperseded,
                chunkStrategyValue.dbValue(),
                null,
                detectConflicts);

        SearchResponse response = searchService.search(request);
        List<SearchResultItem> results = response.results() == null
                ? List.of()
                : response.results();
        if (results.size() > MAX_MCP_RESULTS) {
            results = results.subList(0, MAX_MCP_RESULTS);
        }

        AbstainReason reason = response.abstainReason() == null
                ? AbstainReason.NONE
                : AbstainReason.valueOf(response.abstainReason());
        if (results.isEmpty() && reason == AbstainReason.NONE) {
            // Defensive: an upstream empty result must still be presented as NO_MATCH rather than
            // as "empty results + empty message".
            reason = AbstainReason.NO_MATCH;
        }
        boolean abstained = reason.abstained();
        // This is the current system fallback call point. When a generation layer is added, its
        // text should be passed as the first argument; a blank model answer then becomes the
        // deterministic abstain message instead of an empty reply. No LLM is wired in Phase 7.
        String message = abstained ? answerFallback.fallback(response.message(), reason) : null;
        AbstainDiagnostics diagnostics = response.diagnostics() == null
                ? AbstainDiagnostics.emptyNoMatch()
                : response.diagnostics();
        if (!reason.name().equals(diagnostics.reason())) {
            diagnostics = diagnostics.withReason(reason.name());
        }

        return successResult(searchBody(results, abstained, abstained ? reason.name() : null,
                message, diagnostics));
    }

    private Map<String, Object> searchBody(List<SearchResultItem> results,
                                           boolean abstained,
                                           String abstainReason,
                                           String message,
                                           AbstainDiagnostics diagnostics) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        // P0 ignores detectConflicts (docs/02-api-contract.md 3.2); always return an empty array.
        body.put("conflicts", List.of());
        // Phase 7 additive fields. They are present on both abstained and normal responses.
        body.put("abstained", abstained);
        body.put("abstainReason", abstainReason);
        body.put("message", message);
        body.put("diagnostics", diagnostics);
        return body;
    }

    // ---------------------------------------------------------------------------------------
    // get_document
    // ---------------------------------------------------------------------------------------

    private McpSchema.CallToolResult doGetDocument(Map<String, Object> arguments, AuditContext context) {
        String mandateIdRaw = optionalString(arguments, "mandateId");
        if (mandateIdRaw == null || mandateIdRaw.isBlank()) {
            throw new McpToolError(ERR_MANDATE_REQUIRED, "缺少 mandateId，MCP 调用默认拒绝");
        }
        UUID mandateId = parseUuid("mandateId", mandateIdRaw.trim());
        context.mandateId = mandateId;

        MandateRecord mandate = requireMandate(mandateId, TOOL_DOCUMENT, context);

        String docIdRaw = optionalString(arguments, "docId");
        if (docIdRaw == null || docIdRaw.isBlank()) {
            throw invalidParams("docId 必填");
        }
        UUID docId = parseUuid("docId", docIdRaw.trim());
        if (!allowsDocument(mandate, docId)) {
            throw new McpToolError(ERR_MANDATE_SCOPE, "docId 不在 mandate 允许的文档范围内");
        }

        DocumentDetail detail = documentService.detail(docId);
        DocumentRecord document = detail.document();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", document.id().toString());
        body.put("docKey", document.docKey());
        body.put("title", document.title());
        body.put("sourceType", document.sourceType());
        body.put("version", document.version());
        body.put("status", document.status());
        body.put("effectiveFrom", document.effectiveFrom());
        body.put("effectiveTo", document.effectiveTo());
        body.put("chunkCount", detail.totalChunks());
        body.put("createdAt", document.createdAt());
        return successResult(body);
    }

    // ---------------------------------------------------------------------------------------
    // Mandate enforcement (docs/08-governance.md 5)
    // ---------------------------------------------------------------------------------------

    private MandateRecord requireMandate(UUID mandateId, String tool, AuditContext context) {
        MandateRecord mandate = workItemRepository.findMandateById(mandateId)
                .orElseThrow(() -> new McpToolError(ERR_MANDATE_REQUIRED,
                        "mandate 不存在，MCP 调用默认拒绝"));
        context.workItemId = mandate.workItemId();

        if (!MandatePolicy.isActive(mandate, OffsetDateTime.now())) {
            throw new McpToolError(ERR_MANDATE_EXPIRED, "mandate 已过期或不是 ACTIVE 状态");
        }
        if (!MandatePolicy.allowsTool(mandate, tool)) {
            throw new McpToolError(ERR_MANDATE_SCOPE, "工具 " + tool + " 不在 mandate 允许范围内");
        }
        return mandate;
    }

    /**
     * Validates the explicit {@code docIds} against the mandate and returns the document filter
     * that must be pushed into SQL. A null mandate scope means all documents, so an absent
     * request stays unfiltered. An empty mandate scope means no document at all.
     */
    private List<UUID> enforceDocScope(MandateRecord mandate, List<UUID> requestedDocIds) {
        List<UUID> requested = requestedDocIds == null ? List.of() : requestedDocIds;
        if (mandate.allowedDocIds() == null) {
            return requested;
        }
        if (mandate.allowedDocIds().isEmpty()) {
            if (!requested.isEmpty()) {
                throw new McpToolError(ERR_MANDATE_SCOPE,
                        "docIds 超出 mandate 允许的文档范围");
            }
            return List.of();
        }
        Set<UUID> allowed = new LinkedHashSet<>(mandate.allowedDocIds());
        if (!requested.isEmpty() && !allowed.containsAll(requested)) {
            throw new McpToolError(ERR_MANDATE_SCOPE, "docIds 超出 mandate 允许的文档范围");
        }
        return requested.isEmpty() ? mandate.allowedDocIds() : requested;
    }

    private static boolean allowsDocument(MandateRecord mandate, UUID docId) {
        if (mandate.allowedDocIds() == null) {
            return true;
        }
        return mandate.allowedDocIds().contains(docId);
    }

    // ---------------------------------------------------------------------------------------
    // Argument parsing. Malformed JSON-RPC arguments are -32602; business errors stay isError.
    // ---------------------------------------------------------------------------------------

    private static Object raw(Map<String, Object> arguments, String name) {
        return arguments == null ? null : arguments.get(name);
    }

    private static String optionalString(Map<String, Object> arguments, String name) {
        Object value = raw(arguments, name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalidParams(name + " 必须是字符串");
        }
        return text;
    }

    private static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = raw(arguments, name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean flag)) {
            throw invalidParams(name + " 必须是布尔值");
        }
        return flag;
    }

    private static int optionalInt(Map<String, Object> arguments, String name, int defaultValue,
                                   int min, int max) {
        Object value = raw(arguments, name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw invalidParams(name + " 必须是整数");
        }
        double asDouble = number.doubleValue();
        int asInt = number.intValue();
        if (asDouble != asInt || asInt < min || asInt > max) {
            throw invalidParams(name + " 必须是 " + min + "~" + max + " 之间的整数");
        }
        return asInt;
    }

    private static List<UUID> optionalDocIds(Map<String, Object> arguments, String name) {
        Object value = raw(arguments, name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw invalidParams(name + " 必须是 UUID 数组");
        }
        if (items.size() > SearchRequestValidator.MAX_DOC_IDS) {
            throw invalidParams(name + " 最多 50 个");
        }
        List<UUID> docIds = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalidParams(name + " 必须都是合法 UUID");
            }
            docIds.add(parseUuid(name, text.trim()));
        }
        return List.copyOf(docIds);
    }

    private static UUID parseUuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalidParams(field + " 必须是合法 UUID");
        }
    }

    private static void validateAsOf(String asOf) {
        final LocalDate parsed;
        try {
            parsed = LocalDate.parse(asOf.trim());
        } catch (DateTimeParseException ex) {
            throw invalidParams("asOf 必须为 YYYY-MM-DD");
        }
        if (parsed.isBefore(SearchRequestValidator.MIN_AS_OF)
                || parsed.isAfter(SearchRequestValidator.MAX_AS_OF)) {
            throw invalidParams("asOf 必须在 1900-01-01 ~ 2100-12-31 之间");
        }
    }

    private static McpInvalidParamsException invalidParams(String message) {
        return new McpInvalidParamsException(message);
    }

    // ---------------------------------------------------------------------------------------
    // Result/audit plumbing
    // ---------------------------------------------------------------------------------------

    private McpSchema.CallToolResult execute(String toolName,
                                             Map<String, Object> arguments,
                                             ToolCall call) {
        long startedAt = System.nanoTime();
        AuditContext context = new AuditContext(auditService.argsHash(arguments));
        try {
            return call.run(context);
        } catch (McpInvalidParamsException ex) {
            context.errorCode = String.valueOf(JSONRPC_INVALID_PARAMS);
            throw McpError.builder(JSONRPC_INVALID_PARAMS).message(ex.getMessage()).build();
        } catch (McpToolError ex) {
            context.errorCode = ex.code();
            return errorResult(ex.code(), ex.getMessage());
        } catch (ApiException ex) {
            context.errorCode = ex.errorCode().code();
            return errorResult(ex.errorCode().code(), ex.getMessage());
        } catch (DataAccessException ex) {
            context.errorCode = ErrorCode.DB_UNAVAILABLE.code();
            log.error("mcp tool database failure tool={} mandateId={}", toolName, context.mandateId, ex);
            return errorResult(context.errorCode, "数据库暂时不可用，请稍后重试");
        } catch (RuntimeException ex) {
            context.errorCode = String.valueOf(JSONRPC_INTERNAL_ERROR);
            log.error("mcp tool internal failure tool={} mandateId={}", toolName, context.mandateId, ex);
            throw McpError.builder(JSONRPC_INTERNAL_ERROR).message("服务内部错误").build();
        } finally {
            String status = context.errorCode == null
                    ? McpAuditEntry.STATUS_OK
                    : McpAuditEntry.STATUS_ERROR;
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            auditService.record(toolName, context.workItemId, context.mandateId, context.argsHash,
                    status, context.errorCode, latencyMs);
        }
    }

    private McpSchema.CallToolResult successResult(Object body) {
        try {
            return new McpSchema.CallToolResult(objectMapper.writeValueAsString(body), false);
        } catch (JsonProcessingException ex) {
            // Defensive: never report a serialization failure as a successful (isError=false) call.
            return errorResult(ErrorCode.INTERNAL.code(), "服务内部错误");
        }
    }

    private McpSchema.CallToolResult errorResult(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return new McpSchema.CallToolResult(toJson(body), true);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            // Defensive: the payloads are plain maps/lists/records.
            return "{\"code\":\"ERR-INTERNAL\",\"message\":\"服务内部错误\"}";
        }
    }

    @FunctionalInterface
    private interface ToolCall {
        McpSchema.CallToolResult run(AuditContext context);
    }

    private static final class AuditContext {
        private final String argsHash;
        private UUID workItemId;
        private UUID mandateId;
        private String errorCode;

        private AuditContext(String argsHash) {
            this.argsHash = argsHash;
        }
    }

    /** Expected business failure: returned as {@code isError=true} with a stable code. */
    private static final class McpToolError extends RuntimeException {
        private final String code;

        private McpToolError(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    /** Malformed JSON-RPC arguments: mapped to JSON-RPC -32602. */
    private static final class McpInvalidParamsException extends RuntimeException {
        private McpInvalidParamsException(String message) {
            super(message);
        }
    }
}
