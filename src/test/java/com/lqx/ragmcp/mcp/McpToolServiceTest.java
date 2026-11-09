package com.lqx.ragmcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqx.ragmcp.document.DocumentService;
import com.lqx.ragmcp.governance.WorkItemRepository;
import com.lqx.ragmcp.search.SearchService;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

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
    private final McpToolService service = new McpToolService(
            repository, searchService, documentService, auditService,
            new ObjectMapper().findAndRegisterModules());

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
}
