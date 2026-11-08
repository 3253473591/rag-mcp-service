package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With no ADMIN_TOKEN configured the application must still start, but every approve/deny must
 * fail closed with 401 even if the caller supplies some token.
 */
@TestPropertySource(properties = "app.admin.token=")
class GovernanceAdminUnconfiguredIntegrationTest extends AbstractIntegrationTest {

    private static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @BeforeEach
    void resetGovernanceTables() {
        jdbc.execute("TRUNCATE mandate, work_item CASCADE");
    }

    @Test
    void unconfiguredTokenRejectsAllApprovalsAndDenials() throws Exception {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("requester", "agent-report-writer");
        createBody.put("purpose", "报告生成");
        createBody.put("requestedTools", List.of("search_knowledge"));
        createBody.put("requestedDocIds", List.of(DOC_A.toString()));
        createBody.put("requestedTopK", 5);

        MvcResult createdResult = mockMvc.perform(post("/api/v1/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(
                createdResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("workItemId").asText());

        JsonNode approve = postJson("/api/v1/work-items/" + id + "/approve",
                Map.of(), "anything", 401);
        assertThat(approve.get("code").asText()).isEqualTo("ERR-ADMIN_UNAUTHORIZED");

        JsonNode deny = postJson("/api/v1/work-items/" + id + "/deny",
                Map.of("reason", "x"), "test-admin", 401);
        assertThat(deny.get("code").asText()).isEqualTo("ERR-ADMIN_UNAUTHORIZED");

        String status = jdbc.queryForObject(
                "SELECT status FROM work_item WHERE id = ?::uuid", String.class, id.toString());
        assertThat(status).isEqualTo("PENDING");
    }

    private JsonNode postJson(String path, Map<String, Object> body, String token, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Admin-Token", token)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
