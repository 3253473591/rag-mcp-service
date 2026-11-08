package com.lqx.ragmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lqx.ragmcp.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 governance API (T-GOV-01..07) against real PostgreSQL/pgvector.
 *
 * <p>Covers contract fields, explicit-null vs absent {@code allowedDocIds}, narrowing-only
 * approval, deny semantics, 401/404/409 paths and the approve transaction rollback.</p>
 */
@TestPropertySource(properties = "app.admin.token=test-admin")
class GovernanceIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin";
    private static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID DOC_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID DOC_C = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @BeforeEach
    void resetGovernanceTables() {
        jdbc.execute("TRUNCATE mandate, work_item CASCADE");
    }

    // ---------------------------------------------------------------------------------------
    // T-GOV-01 / T-GOV-02: create and read
    // ---------------------------------------------------------------------------------------

    @Test
    void tGov01_createReturns201PendingAndPersistsJsonb() throws Exception {
        Map<String, Object> body = baseBody();
        body.put("requestedDocIds", List.of(DOC_A.toString(), DOC_B.toString()));
        body.put("requestedTopK", 8);

        JsonNode created = postJson("/api/v1/work-items", body, null, 201);

        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("createdAt").asText()).isNotBlank();
        UUID id = UUID.fromString(created.get("workItemId").asText());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT requester, purpose, requested_tools::text AS tools,
                       requested_doc_ids::text AS docs, requested_top_k, status
                FROM work_item WHERE id = ?::uuid
                """, id.toString());
        assertThat(row.get("requester")).isEqualTo("agent-report-writer");
        assertThat(row.get("purpose")).isEqualTo("为体检报告生成检索依据");
        assertThat(row.get("requested_top_k")).isEqualTo(8);
        assertThat(row.get("status")).isEqualTo("PENDING");

        JsonNode tools = objectMapper.readTree((String) row.get("tools"));
        assertThat(tools.get(0).asText()).isEqualTo("search_knowledge");
        assertThat(tools.get(1).asText()).isEqualTo("get_document");

        JsonNode docs = objectMapper.readTree((String) row.get("docs"));
        assertThat(docs.get(0).asText()).isEqualTo(DOC_A.toString());
        assertThat(docs.get(1).asText()).isEqualTo(DOC_B.toString());

        JsonNode fetched = getWorkItem(id, 200);
        assertThat(fetched.get("workItemId").asText()).isEqualTo(id.toString());
        assertThat(fetched.get("requestedTopK").asInt()).isEqualTo(8);
        assertThat(fetched.get("mandate").isNull()).isTrue();
    }

    @Test
    void tGov02_getReturnsContractFieldsDefaultsAndNotFounds() throws Exception {
        Map<String, Object> body = baseBody();
        body.remove("requestedTopK");
        UUID id = create(body);

        JsonNode fetched = getWorkItem(id, 200);
        assertThat(fetched.get("requester").asText()).isEqualTo("agent-report-writer");
        assertThat(fetched.get("purpose").asText()).isEqualTo("为体检报告生成检索依据");
        assertThat(fetched.get("requestedTools").get(0).asText()).isEqualTo("search_knowledge");
        assertThat(fetched.get("requestedTools").get(1).asText()).isEqualTo("get_document");
        assertThat(fetched.get("requestedDocIds").isNull()).isTrue();
        assertThat(fetched.get("requestedTopK").asInt()).isEqualTo(5);
        assertThat(fetched.get("status").asText()).isEqualTo("PENDING");
        assertThat(fetched.get("decidedBy").isNull()).isTrue();
        assertThat(fetched.get("decidedAt").isNull()).isTrue();
        assertThat(fetched.get("decisionReason").isNull()).isTrue();
        assertThat(fetched.get("createdAt").asText()).isNotBlank();

        JsonNode notFound = getJson("/api/v1/work-items/" + UUID.randomUUID(), 404);
        assertApiError(notFound, "ERR-WORKITEM_NOT_FOUND");

        JsonNode invalid = getJson("/api/v1/work-items/not-a-uuid", 400);
        assertApiError(invalid, "ERR-VALIDATION");
        assertThat(invalid.get("details").get("field").asText()).isEqualTo("workItemId");
    }

    // ---------------------------------------------------------------------------------------
    // T-GOV-03 / T-GOV-04: approve and deny
    // ---------------------------------------------------------------------------------------

    @Test
    void tGov03_approveCreatesAtomicActiveMandateWithNarrowedScope() throws Exception {
        Map<String, Object> createBody = baseBody();
        createBody.put("requestedDocIds", List.of(DOC_A.toString(), DOC_B.toString()));
        UUID id = create(createBody);

        Map<String, Object> approveBody = new LinkedHashMap<>();
        approveBody.put("ttlSeconds", 120);
        approveBody.put("allowedTools", List.of("search_knowledge"));
        approveBody.put("allowedDocIds", List.of(DOC_A.toString()));
        approveBody.put("maxTopK", 3);
        approveBody.put("reason", "报告生成场景，限高血压指南");

        JsonNode approved = postJson(approvePath(id), approveBody, ADMIN_TOKEN, 200);

        assertThat(approved.get("workItemId").asText()).isEqualTo(id.toString());
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        JsonNode mandate = approved.get("mandate");
        assertThat(mandate.get("mandateId").asText()).isNotBlank();
        assertThat(mandate.get("allowedTools")).hasSize(1);
        assertThat(mandate.get("allowedTools").get(0).asText()).isEqualTo("search_knowledge");
        assertThat(mandate.get("allowedDocIds").get(0).asText()).isEqualTo(DOC_A.toString());
        assertThat(mandate.get("maxTopK").asInt()).isEqualTo(3);

        OffsetDateTime expiresAt = OffsetDateTime.parse(mandate.get("expiresAt").asText());
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(expiresAt).isBetween(now.plusSeconds(110), now.plusSeconds(130));

        Map<String, Object> workItemRow = jdbc.queryForMap("""
                SELECT status, decided_by, decided_at, decision_reason
                FROM work_item WHERE id = ?::uuid
                """, id.toString());
        assertThat(workItemRow.get("status")).isEqualTo("APPROVED");
        assertThat(workItemRow.get("decided_by")).isEqualTo("admin");
        assertThat(workItemRow.get("decided_at")).isNotNull();
        assertThat(workItemRow.get("decision_reason")).isEqualTo("报告生成场景，限高血压指南");

        Map<String, Object> mandateRow = jdbc.queryForMap("""
                SELECT status, requester, allowed_tools::text AS tools,
                       allowed_doc_ids::text AS docs, max_top_k
                FROM mandate WHERE work_item_id = ?::uuid
                """, id.toString());
        assertThat(mandateRow.get("status")).isEqualTo("ACTIVE");
        assertThat(mandateRow.get("requester")).isEqualTo("agent-report-writer");
        assertThat(mandateRow.get("max_top_k")).isEqualTo(3);
        assertThat(objectMapper.readTree((String) mandateRow.get("tools")).get(0).asText())
                .isEqualTo("search_knowledge");
        assertThat(objectMapper.readTree((String) mandateRow.get("docs")).get(0).asText())
                .isEqualTo(DOC_A.toString());

        JsonNode fetched = getWorkItem(id, 200);
        assertThat(fetched.get("status").asText()).isEqualTo("APPROVED");
        assertThat(fetched.get("decidedBy").asText()).isEqualTo("admin");
        assertThat(fetched.get("decidedAt").asText()).isNotBlank();
        assertThat(fetched.get("decisionReason").asText()).isEqualTo("报告生成场景，限高血压指南");
        assertThat(fetched.get("mandate").get("mandateId").asText())
                .isEqualTo(mandate.get("mandateId").asText());
        assertThat(fetched.get("mandate").get("maxTopK").asInt()).isEqualTo(3);
    }

    @Test
    void tGov04_denyTransitionsToDeniedWithoutMandate() throws Exception {
        UUID id = createPending(List.of(DOC_A.toString()));

        JsonNode denied = postJson(denyPath(id), Map.of("reason", "申请范围过大"),
                ADMIN_TOKEN, 200);
        assertThat(denied.get("workItemId").asText()).isEqualTo(id.toString());
        assertThat(denied.get("status").asText()).isEqualTo("DENIED");
        assertThat(denied.get("decidedAt").asText()).isNotBlank();

        JsonNode fetched = getWorkItem(id, 200);
        assertThat(fetched.get("status").asText()).isEqualTo("DENIED");
        assertThat(fetched.get("decidedBy").asText()).isEqualTo("admin");
        assertThat(fetched.get("decisionReason").asText()).isEqualTo("申请范围过大");
        assertThat(fetched.get("mandate").isNull()).isTrue();
        assertThat(countMandates(id)).isZero();

        JsonNode deniedAgain = postJson(denyPath(id), Map.of("reason", "again"), ADMIN_TOKEN, 409);
        assertApiError(deniedAgain, "ERR-APPROVAL_INVALID");
        JsonNode approveAfterDeny = postJson(approvePath(id),
                Map.of("allowedDocIds", List.of(DOC_A.toString())), ADMIN_TOKEN, 409);
        assertApiError(approveAfterDeny, "ERR-APPROVAL_INVALID");
        assertThat(workItemStatus(id)).isEqualTo("DENIED");
    }

    @Test
    void tGov05_duplicateApproveAndCrossDecisionsReturn409WithSingleMandate() throws Exception {
        UUID id = createPending(List.of(DOC_A.toString()));

        JsonNode first = postJson(approvePath(id),
                Map.of("allowedDocIds", List.of(DOC_A.toString())), ADMIN_TOKEN, 200);
        assertThat(first.get("status").asText()).isEqualTo("APPROVED");

        JsonNode second = postJson(approvePath(id), Map.of(), ADMIN_TOKEN, 409);
        assertApiError(second, "ERR-APPROVAL_INVALID");
        JsonNode denyAfterApprove = postJson(denyPath(id), Map.of(), ADMIN_TOKEN, 409);
        assertApiError(denyAfterApprove, "ERR-APPROVAL_INVALID");

        assertThat(countMandates(id)).isEqualTo(1);
        assertThat(workItemStatus(id)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------------------------------
    // T-GOV-06: approval can only narrow
    // ---------------------------------------------------------------------------------------

    @Test
    void tGov06_approveCannotExpandToolsDocsTopKOrTtl() throws Exception {
        Map<String, Object> createBody = baseBody();
        createBody.put("requestedTools", List.of("search_knowledge"));
        createBody.put("requestedDocIds", List.of(DOC_A.toString()));
        createBody.put("requestedTopK", 5);
        UUID id = create(createBody);

        assertApiError(postJson(approvePath(id),
                Map.of("allowedTools", List.of("search_knowledge", "get_document")),
                ADMIN_TOKEN, 400), "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id),
                Map.of("allowedTools", List.of("delete_document")), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id),
                Map.of("allowedDocIds", List.of(DOC_B.toString())), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id),
                Map.of("allowedDocIds", List.of("not-a-uuid")), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id), Map.of("maxTopK", 6), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id), Map.of("maxTopK", 0), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id), Map.of("ttlSeconds", 59), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");
        assertApiError(postJson(approvePath(id), Map.of("ttlSeconds", 86_401), ADMIN_TOKEN, 400),
                "ERR-VALIDATION");

        assertThat(workItemStatus(id)).isEqualTo("PENDING");
        assertThat(countMandates(id)).isZero();
    }

    @Test
    void tGov06b_ttlBoundsAreAcceptedAtTheEdges() throws Exception {
        UUID low = createPending(List.of(DOC_A.toString()));
        JsonNode lowResponse = postJson(approvePath(low), Map.of("ttlSeconds", 60), ADMIN_TOKEN, 200);
        assertThat(lowResponse.get("status").asText()).isEqualTo("APPROVED");

        UUID high = createPending(List.of(DOC_A.toString()));
        JsonNode highResponse = postJson(approvePath(high), Map.of("ttlSeconds", 86_400), ADMIN_TOKEN, 200);
        assertThat(highResponse.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void tGov06c_defaultsCopyRequestedScopeAndDefaultTtl() throws Exception {
        Map<String, Object> createBody = baseBody();
        createBody.put("requestedDocIds", List.of(DOC_A.toString(), DOC_B.toString()));
        createBody.put("requestedTopK", 4);
        UUID id = create(createBody);

        JsonNode approved = postJson(approvePath(id), Map.of(), ADMIN_TOKEN, 200);
        JsonNode mandate = approved.get("mandate");
        assertThat(mandate.get("allowedTools")).hasSize(2);
        assertThat(mandate.get("allowedDocIds")).hasSize(2);
        assertThat(mandate.get("maxTopK").asInt()).isEqualTo(4);

        OffsetDateTime expiresAt = OffsetDateTime.parse(mandate.get("expiresAt").asText());
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(expiresAt).isBetween(now.plusSeconds(3590), now.plusSeconds(3610));
    }

    // ---------------------------------------------------------------------------------------
    // Explicit allowedDocIds semantics
    // ---------------------------------------------------------------------------------------

    @Test
    void explicitDocs_allScopeRequiresFieldAndExplicitNullMeansAll() throws Exception {
        UUID id = createPending(null); // requestedDocIds omitted -> all documents

        JsonNode missing = postJson(approvePath(id), Map.of(), ADMIN_TOKEN, 400);
        assertApiError(missing, "ERR-VALIDATION");
        assertThat(missing.get("details").get("field").asText()).isEqualTo("allowedDocIds");
        assertThat(workItemStatus(id)).isEqualTo("PENDING");
        assertThat(countMandates(id)).isZero();

        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("allowedDocIds", null);
        JsonNode approved = postJson(approvePath(id), explicitNull, ADMIN_TOKEN, 200);

        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("mandate").get("allowedDocIds").isNull()).isTrue();
        assertThat(allowedDocIdsJson(id)).isNull();

        JsonNode fetched = getWorkItem(id, 200);
        assertThat(fetched.get("requestedDocIds").isNull()).isTrue();
        assertThat(fetched.get("mandate").get("allowedDocIds").isNull()).isTrue();
    }

    @Test
    void explicitDocs_allScopeWithArrayIsSubset() throws Exception {
        UUID id = createPending(null);

        JsonNode approved = postJson(approvePath(id),
                Map.of("allowedDocIds", List.of(DOC_C.toString())), ADMIN_TOKEN, 200);

        assertThat(approved.get("mandate").get("allowedDocIds").get(0).asText())
                .isEqualTo(DOC_C.toString());
        JsonNode stored = objectMapper.readTree(allowedDocIdsJson(id));
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).asText()).isEqualTo(DOC_C.toString());
    }

    @Test
    void explicitDocs_emptyScopeDefaultsToEmptyAndRejectsNullOrOtherDocs() throws Exception {
        UUID defaults = createPending(List.of());
        JsonNode approved = postJson(approvePath(defaults), Map.of(), ADMIN_TOKEN, 200);
        assertThat(approved.get("mandate").get("allowedDocIds")).isEmpty();
        assertThat(allowedDocIdsJson(defaults)).isEqualTo("[]");

        UUID explicitNull = createPending(List.of());
        Map<String, Object> nullBody = new LinkedHashMap<>();
        nullBody.put("allowedDocIds", null);
        JsonNode nullResponse = postJson(approvePath(explicitNull), nullBody, ADMIN_TOKEN, 400);
        assertApiError(nullResponse, "ERR-VALIDATION");
        assertThat(nullResponse.get("details").get("field").asText()).isEqualTo("allowedDocIds");

        UUID otherDocs = createPending(List.of());
        JsonNode otherResponse = postJson(approvePath(otherDocs),
                Map.of("allowedDocIds", List.of(DOC_A.toString())), ADMIN_TOKEN, 400);
        assertApiError(otherResponse, "ERR-VALIDATION");

        assertThat(workItemStatus(explicitNull)).isEqualTo("PENDING");
        assertThat(workItemStatus(otherDocs)).isEqualTo("PENDING");
    }

    @Test
    void explicitDocs_requestedDocsDefaultToRequestedAndRejectExplicitNull() throws Exception {
        UUID defaults = createPending(List.of(DOC_A.toString(), DOC_B.toString()));
        JsonNode approved = postJson(approvePath(defaults), Map.of(), ADMIN_TOKEN, 200);
        JsonNode allowed = approved.get("mandate").get("allowedDocIds");
        assertThat(allowed).hasSize(2);
        assertThat(allowed.get(0).asText()).isEqualTo(DOC_A.toString());
        assertThat(allowed.get(1).asText()).isEqualTo(DOC_B.toString());

        UUID explicitNull = createPending(List.of(DOC_A.toString()));
        Map<String, Object> nullBody = new LinkedHashMap<>();
        nullBody.put("allowedDocIds", null);
        JsonNode nullResponse = postJson(approvePath(explicitNull), nullBody, ADMIN_TOKEN, 400);
        assertApiError(nullResponse, "ERR-VALIDATION");

        UUID subset = createPending(List.of(DOC_A.toString(), DOC_B.toString()));
        JsonNode subsetResponse = postJson(approvePath(subset),
                Map.of("allowedDocIds", List.of(DOC_A.toString())), ADMIN_TOKEN, 200);
        assertThat(subsetResponse.get("mandate").get("allowedDocIds")).hasSize(1);
        assertThat(workItemStatus(explicitNull)).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------------------------------
    // T-GOV-07: admin authentication
    // ---------------------------------------------------------------------------------------

    @Test
    void tGov07_approveAndDenyRequireAdminToken() throws Exception {
        UUID id = createPending(List.of(DOC_A.toString()));

        JsonNode noTokenApprove = postJson(approvePath(id), Map.of(), null, 401);
        assertApiError(noTokenApprove, "ERR-ADMIN_UNAUTHORIZED");

        JsonNode wrongTokenApprove = postJson(approvePath(id), Map.of(), "wrong", 401);
        assertApiError(wrongTokenApprove, "ERR-ADMIN_UNAUTHORIZED");

        JsonNode noTokenDeny = postJson(denyPath(id), Map.of("reason", "x"), null, 401);
        assertApiError(noTokenDeny, "ERR-ADMIN_UNAUTHORIZED");

        JsonNode wrongTokenDeny = postJson(denyPath(id), Map.of(), "admin", 401);
        assertApiError(wrongTokenDeny, "ERR-ADMIN_UNAUTHORIZED");

        assertThat(workItemStatus(id)).isEqualTo("PENDING");
        assertThat(countMandates(id)).isZero();

        JsonNode approved = postJson(approvePath(id), Map.of(), ADMIN_TOKEN, 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void approveAndDenyReturn404ForUnknownWorkItemAnd400ForInvalidUuid() throws Exception {
        UUID unknown = UUID.randomUUID();
        assertApiError(postJson(approvePath(unknown), Map.of(), ADMIN_TOKEN, 404),
                "ERR-WORKITEM_NOT_FOUND");
        assertApiError(postJson(denyPath(unknown), Map.of(), ADMIN_TOKEN, 404),
                "ERR-WORKITEM_NOT_FOUND");

        assertApiError(postJson("/api/v1/work-items/not-a-uuid/approve", Map.of(),
                ADMIN_TOKEN, 400), "ERR-VALIDATION");
        assertApiError(postJson("/api/v1/work-items/not-a-uuid/deny", Map.of(),
                ADMIN_TOKEN, 400), "ERR-VALIDATION");
    }

    // ---------------------------------------------------------------------------------------
    // Create validation boundaries
    // ---------------------------------------------------------------------------------------

    @Test
    void createValidationRejectsBadFieldsWithoutWritingRows() throws Exception {
        assertCreateRejected(bodyWithout("requester"), "requester");
        assertCreateRejected(with(body -> body.put("requester", "   ")), "requester");
        assertCreateRejected(bodyWithout("purpose"), "purpose");
        assertCreateRejected(with(body -> body.put("purpose", "p".repeat(501))), "purpose");
        assertCreateRejected(bodyWithout("requestedTools"), "requestedTools");
        assertCreateRejected(with(body -> body.put("requestedTools", List.of())), "requestedTools");
        assertCreateRejected(with(body -> body.put("requestedTools", List.of(
                "search_knowledge", "get_document", "search_knowledge",
                "get_document", "search_knowledge", "get_document"))), "requestedTools");
        assertCreateRejected(with(body -> body.put("requestedTools", List.of("delete_document"))),
                "requestedTools");
        assertCreateRejected(with(body -> body.put("requestedDocIds", manyDocIds(51))),
                "requestedDocIds");
        assertCreateRejected(with(body -> body.put("requestedDocIds", List.of("not-a-uuid"))),
                "requestedDocIds");
        assertCreateRejected(with(body -> body.put("requestedTopK", 0)), "requestedTopK");
        assertCreateRejected(with(body -> body.put("requestedTopK", 21)), "requestedTopK");

        Long count = jdbc.queryForObject("SELECT count(*) FROM work_item", Long.class);
        assertThat(count).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Transaction rollback
    // ---------------------------------------------------------------------------------------

    @Test
    void approveRollsBackWorkItemWhenMandateInsertFails() throws Exception {
        UUID id = createPending(List.of(DOC_A.toString()));

        // Simulate an existing mandate (e.g. a lost/duplicated approval attempt). The unique
        // constraint on mandate.work_item_id rejects the second insert after the status update.
        jdbc.update("""
                INSERT INTO mandate
                  (id, work_item_id, requester, allowed_tools, allowed_doc_ids, max_top_k,
                   status, expires_at)
                VALUES
                  (?::uuid, ?::uuid, 'agent-report-writer', '["search_knowledge"]'::jsonb,
                   ?::jsonb, 5, 'ACTIVE', now() + interval '1 hour')
                """,
                UUID.randomUUID().toString(),
                id.toString(),
                objectMapper.writeValueAsString(List.of(DOC_A.toString())));

        JsonNode response = postJson(approvePath(id), Map.of(), ADMIN_TOKEN, 409);
        assertApiError(response, "ERR-APPROVAL_INVALID");

        assertThat(workItemStatus(id)).isEqualTo("PENDING");
        assertThat(countMandates(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM work_item WHERE id = ?::uuid AND status = 'APPROVED'",
                Integer.class, id.toString())).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requester", "agent-report-writer");
        body.put("purpose", "为体检报告生成检索依据");
        body.put("requestedTools", List.of("search_knowledge", "get_document"));
        body.put("requestedTopK", 5);
        return body;
    }

    private static Map<String, Object> bodyWithout(String field) {
        Map<String, Object> body = baseBody();
        body.remove(field);
        return body;
    }

    private static Map<String, Object> with(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> body = baseBody();
        customizer.accept(body);
        return body;
    }

    private static List<String> manyDocIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    private UUID createPending(List<String> requestedDocIds) throws Exception {
        Map<String, Object> body = baseBody();
        if (requestedDocIds != null) {
            body.put("requestedDocIds", requestedDocIds);
        }
        return create(body);
    }

    private UUID create(Map<String, Object> body) throws Exception {
        JsonNode response = postJson("/api/v1/work-items", body, null, 201);
        assertThat(response.get("status").asText()).isEqualTo("PENDING");
        return UUID.fromString(response.get("workItemId").asText());
    }

    private void assertCreateRejected(Map<String, Object> body, String expectedField) throws Exception {
        JsonNode response = postJson("/api/v1/work-items", body, null, 400);
        assertApiError(response, "ERR-VALIDATION");
        assertThat(response.get("details").get("field").asText()).isEqualTo(expectedField);
    }

    private JsonNode postJson(String path, Map<String, Object> body, String token, int expectedStatus)
            throws Exception {
        MockHttpServletRequestBuilder builder = post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(objectMapper.writeValueAsString(body));
        }
        if (token != null) {
            builder.header("X-Admin-Token", token);
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return readJson(result);
    }

    private JsonNode getWorkItem(UUID id, int expectedStatus) throws Exception {
        return getJson("/api/v1/work-items/" + id, expectedStatus);
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return readJson(result);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertApiError(JsonNode error, String expectedCode) {
        assertThat(error.get("code").asText()).isEqualTo(expectedCode);
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("traceId").asText()).isNotBlank();
    }

    private String workItemStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM work_item WHERE id = ?::uuid", String.class, id.toString());
    }

    private int countMandates(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM mandate WHERE work_item_id = ?::uuid",
                Integer.class, id.toString());
        return count == null ? 0 : count;
    }

    private String allowedDocIdsJson(UUID id) {
        List<String> values = jdbc.query(
                "SELECT allowed_doc_ids::text FROM mandate WHERE work_item_id = ?::uuid",
                (rs, rowNum) -> rs.getString(1), id.toString());
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private static String approvePath(UUID id) {
        return "/api/v1/work-items/" + id + "/approve";
    }

    private static String denyPath(UUID id) {
        return "/api/v1/work-items/" + id + "/deny";
    }
}
