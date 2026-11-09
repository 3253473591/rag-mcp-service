package com.lqx.ragmcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.api.dto.AbstainDiagnostics;
import com.lqx.ragmcp.api.dto.SearchResponse;
import com.lqx.ragmcp.document.DocumentService;
import com.lqx.ragmcp.governance.MandateRecord;
import com.lqx.ragmcp.governance.WorkItemRepository;
import com.lqx.ragmcp.search.AnswerFallback;
import com.lqx.ragmcp.search.SearchService;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the tool error envelope: expected business errors are {@code isError=true};
 * malformed arguments are JSON-RPC -32602; unexpected failures are -32603 without leaking the
 * cause message.
 */
class McpToolServiceTest {

    private final WorkItemRepository repository = mock(WorkItemRepository.class);
    private final SearchService searchService = mock(SearchService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final McpAuditService auditService = mock(McpAuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final McpToolService service = new McpToolService(
            repository, searchService, documentService, auditService,
            objectMapper, new AnswerFallback());

    @Test
    void missingMandateIdIsABusinessToolError() {
        McpSchema.CallToolResult result = service.searchKnowledge(Map.of("query", "q"));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("ERR-MANDATE_REQUIRED");
    }

    @Test
    void malformedMandateIdIsInvalidParams() {
        Throwable thrown = catchThrowable(
                () -> service.searchKnowledge(Map.of("mandateId", "not-a-uuid", "query", "q")));

        assertThat(thrown).isInstanceOf(McpError.class);
        assertThat(((McpError) thrown).getJsonRpcError().code()).isEqualTo(-32602);
    }

    @Test
    void unexpectedRepositoryFailureIsMinus32603WithoutLeakingTheCause() {
        when(repository.findMandateById(any())).thenThrow(
                new IllegalStateException("secret sql detail must not reach the client"));

        Throwable thrown = catchThrowable(
                () -> service.searchKnowledge(Map.of(
                        "mandateId", UUID.randomUUID().toString(),
                        "query", "q")));

        assertThat(thrown).isInstanceOf(McpError.class);
        McpError error = (McpError) thrown;
        assertThat(error.getJsonRpcError().code()).isEqualTo(-32603);
        assertThat(error.getJsonRpcError().message()).isEqualTo("服务内部错误");
        assertThat(error.getJsonRpcError().message()).doesNotContain("secret");
    }

    @Test
    void abstainedSearchBodyCarriesReasonMessageAndDiagnostics() throws Exception {
        UUID mandateId = activeSearchMandate();
        when(searchService.search(any())).thenReturn(abstainedResponse("公司年假有多少天？"));

        McpSchema.CallToolResult result = service.searchKnowledge(Map.of(
                "mandateId", mandateId.toString(),
                "query", "公司年假有多少天？"));

        assertThat(result.isError()).isFalse();
        JsonNode body = body(result);
        assertThat(body.get("results")).isEmpty();
        assertThat(body.get("abstained").asBoolean()).isTrue();
        assertThat(body.get("abstainReason").asText()).isEqualTo("NO_MATCH");
        assertThat(body.get("message").asText()).isEqualTo(AnswerFallback.NO_MATCH_MESSAGE);
        assertThat(body.get("diagnostics").get("reason").asText()).isEqualTo("NO_MATCH");
        assertThat(body.get("diagnostics").get("topScore").asDouble()).isEqualTo(0.40);
        assertThat(body.get("diagnostics").get("candidateCount").asInt()).isEqualTo(2);
    }

    @Test
    void emptyUpstreamResultStillGetsAnHonestMessageInTheMcpBody() throws Exception {
        UUID mandateId = activeSearchMandate();
        AbstainDiagnostics diagnostics = new AbstainDiagnostics(
                0.0d, 0.0d, 0.0d, 0, 0, 0.55d, 0.63d, 8, 0.02d, true, "NONE", List.of());
        when(searchService.search(any())).thenReturn(new SearchResponse(
                "q", LocalDate.of(2026, 11, 9), "heading", "hybrid", 1L,
                List.of(), List.of(), false, null, null, diagnostics));

        McpSchema.CallToolResult result = service.searchKnowledge(Map.of(
                "mandateId", mandateId.toString(), "query", "q"));

        JsonNode body = body(result);
        assertThat(body.get("results")).isEmpty();
        assertThat(body.get("abstained").asBoolean()).isTrue();
        assertThat(body.get("abstainReason").asText()).isEqualTo("NO_MATCH");
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("diagnostics").get("reason").asText()).isEqualTo("NO_MATCH");
    }

    private UUID activeSearchMandate() {
        UUID mandateId = UUID.randomUUID();
        MandateRecord mandate = new MandateRecord(
                mandateId, UUID.randomUUID(), "test", List.of("search_knowledge"),
                null, 5, "ACTIVE", OffsetDateTime.now().plusHours(1));
        when(repository.findMandateById(mandateId)).thenReturn(java.util.Optional.of(mandate));
        return mandateId;
    }

    private JsonNode body(McpSchema.CallToolResult result) throws Exception {
        return objectMapper.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }

    private static SearchResponse abstainedResponse(String query) {
        AbstainDiagnostics diagnostics = new AbstainDiagnostics(
                0.40d, 0.38d, 0.02d, 2, 0, 0.55d, 0.63d, 8, 0.02d,
                true, "NO_MATCH", List.of(0.40d, 0.38d));
        return new SearchResponse(query, LocalDate.of(2026, 11, 9), "heading", "hybrid",
                1L, List.of(), List.of(), true, "NO_MATCH",
                AnswerFallback.NO_MATCH_MESSAGE, diagnostics);
    }
}
