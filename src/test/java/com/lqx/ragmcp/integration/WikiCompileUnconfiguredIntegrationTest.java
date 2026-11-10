package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed contract: without {@code DEEPSEEK_API_KEY} the compile endpoint must return a clear
 * error and write nothing. It must never emit synthetic/empty wiki pages.
 */
@TestPropertySource(properties = {
        "app.admin.token=test-admin-token",
        "app.wiki.api-key="
})
class WikiCompileUnconfiguredIntegrationTest extends AbstractIntegrationTest {

    @Test
    void missingKeyFailsClosedAndWritesNoWikiPages() throws Exception {
        String docId = upload(file("handbook.md", TestDocuments.markdown()), Map.of(
                "docKey", "handbook",
                "title", "员工手册",
                "version", "2025",
                "effectiveFrom", "2025-01-01",
                "chunkStrategies", "heading"), 201).get("docId").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/admin/pages/compile-wiki")
                        .header("X-Admin-Token", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("docIds", List.of(docId)))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ERR-LLM_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        JsonNode error = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(error.get("message").asText()).contains("DEEPSEEK_API_KEY");
        Integer wikiPages = jdbc.queryForObject(
                "SELECT count(*) FROM kb_page WHERE document_id = ?::uuid AND kind = 'wiki'",
                Integer.class, docId);
        assertThat(wikiPages).isZero();
        // The deterministic page layer is independent and still exists.
        Integer aggregatePages = jdbc.queryForObject(
                "SELECT count(*) FROM kb_page WHERE document_id = ?::uuid AND kind = 'aggregate'",
                Integer.class, docId);
        assertThat(aggregatePages).isPositive();
    }
}
