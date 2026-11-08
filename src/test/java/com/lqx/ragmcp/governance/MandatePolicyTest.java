package com.lqx.ragmcp.governance;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the Phase 4 narrowing rules and the Phase 5-readiness mandate checks.
 * Keeps the explicit-null vs absent allowedDocIds semantics independently testable from HTTP/DB.
 */
class MandatePolicyTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-09T12:00:00+08:00");
    private static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID DOC_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID DOC_C = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Test
    void defaultsKeepRequestedScopeAndUseDefaultTtl() {
        WorkItemRecord item = item(List.of(DOC_A));

        ApprovalDecision decision = policy(item, new ApprovalRequest(null, null, false, null, null, " "));

        assertThat(decision.allowedTools())
                .containsExactly("search_knowledge", "get_document");
        assertThat(decision.allowedDocIds()).containsExactly(DOC_A);
        assertThat(decision.maxTopK()).isEqualTo(5);
        assertThat(decision.ttlSeconds()).isEqualTo(3600);
        assertThat(decision.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(decision.reason()).isNull();
    }

    @Test
    void allDocsRequireExplicitAllowedDocIdsField() {
        WorkItemRecord item = item(null);

        ApiException ex = validation(() -> policy(item,
                new ApprovalRequest(null, null, false, null, null, null)));

        assertThat(ex.details()).containsEntry("field", "allowedDocIds");
    }

    @Test
    void allDocsExplicitNullMeansAll() {
        ApprovalDecision decision = policy(item(null),
                new ApprovalRequest(null, null, true, null, null, null));

        assertThat(decision.allowedDocIds()).isNull();
    }

    @Test
    void allDocsExplicitArrayBecomesSubset() {
        ApprovalDecision decision = policy(item(null),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), null, null));

        assertThat(decision.allowedDocIds()).containsExactly(DOC_A);
    }

    @Test
    void noDocsScopeDefaultsToEmptyAndRejectsExplicitAllOrOtherDocs() {
        ApprovalDecision empty = policy(item(List.of()),
                new ApprovalRequest(null, null, false, null, null, null));
        assertThat(empty.allowedDocIds()).isEmpty();

        ApiException nullDocs = validation(() -> policy(item(List.of()),
                new ApprovalRequest(null, null, true, null, null, null)));
        assertThat(nullDocs.details()).containsEntry("field", "allowedDocIds");

        ApiException otherDocs = validation(() -> policy(item(List.of()),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), null, null)));
        assertThat(otherDocs.details()).containsEntry("field", "allowedDocIds");
    }

    @Test
    void requestedDocsCanBeShrunkButNotEmptyMeaningAll() {
        ApprovalDecision decision = policy(item(List.of(DOC_A, DOC_B)),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), null, null));
        assertThat(decision.allowedDocIds()).containsExactly(DOC_A);

        ApprovalDecision empty = policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, List.of(), null, null));
        assertThat(empty.allowedDocIds()).isEmpty();

        ApiException nullDocs = validation(() -> policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, null, null, null)));
        assertThat(nullDocs.details()).containsEntry("field", "allowedDocIds");
    }

    @Test
    void allowedDocIdsMustBeSubsetOfRequestedDocs() {
        ApiException ex = validation(() -> policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, List.of(DOC_B.toString()), null, null)));

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION);
        assertThat(ex.details()).containsEntry("field", "allowedDocIds");
    }

    @Test
    void allowedDocsAreValidatedForCountAndUuidFormat() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(UUID.randomUUID().toString());
        }
        assertThat(validation(() -> policy(item(null),
                new ApprovalRequest(null, null, true, tooMany, null, null))).details())
                .containsEntry("field", "allowedDocIds");

        assertThat(validation(() -> policy(item(null),
                new ApprovalRequest(null, null, true, List.of("not-a-uuid"), null, null))).details())
                .containsEntry("field", "allowedDocIds");
    }

    @Test
    void allowedToolsMayOnlyShrinkTheRequestedSet() {
        WorkItemRecord item = item(List.of(DOC_A), List.of("search_knowledge"));

        ApprovalDecision narrowed = policy(item,
                new ApprovalRequest(null, List.of("search_knowledge"), true, List.of(DOC_A.toString()), null, null));
        assertThat(narrowed.allowedTools()).containsExactly("search_knowledge");

        assertThat(validation(() -> policy(item,
                new ApprovalRequest(null, List.of("search_knowledge", "get_document"), true,
                        List.of(DOC_A.toString()), null, null))).details())
                .containsEntry("field", "allowedTools");
        assertThat(validation(() -> policy(item,
                new ApprovalRequest(null, List.of("delete_document"), true,
                        List.of(DOC_A.toString()), null, null))).details())
                .containsEntry("field", "allowedTools");
    }

    @Test
    void emptyAllowedToolsIsAllowedButDoesNotGrantAnything() {
        ApprovalDecision decision = policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, List.of(), true, List.of(DOC_A.toString()), null, null));

        assertThat(decision.allowedTools()).isEmpty();
    }

    @Test
    void maxTopKCanShrinkButNotGrow() {
        ApprovalDecision decision = policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), 2, null));
        assertThat(decision.maxTopK()).isEqualTo(2);

        assertThat(validation(() -> policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), 6, null))).details())
                .containsEntry("field", "maxTopK");
        assertThat(validation(() -> policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), 0, null))).details())
                .containsEntry("field", "maxTopK");
        assertThat(validation(() -> policy(item(List.of(DOC_A)),
                new ApprovalRequest(null, null, true, List.of(DOC_A.toString()), 21, null))).details())
                .containsEntry("field", "maxTopK");
    }

    @Test
    void ttlBoundsAreInclusive() {
        assertThat(policy(item(List.of(DOC_A)), requestTtl(60)).ttlSeconds()).isEqualTo(60);
        assertThat(policy(item(List.of(DOC_A)), requestTtl(86_400)).ttlSeconds()).isEqualTo(86_400);
        assertThat(validation(() -> policy(item(List.of(DOC_A)), requestTtl(59))).details())
                .containsEntry("field", "ttlSeconds");
        assertThat(validation(() -> policy(item(List.of(DOC_A)), requestTtl(86_401))).details())
                .containsEntry("field", "ttlSeconds");
    }

    @Test
    void runtimeChecksCoverExpiryToolTopKAndDocScope() {
        MandateRecord active = new MandateRecord(UUID.randomUUID(), UUID.randomUUID(), "req",
                List.of("search_knowledge"), List.of(DOC_A), 3, "ACTIVE",
                NOW.plusSeconds(60));

        assertThat(MandatePolicy.isActive(active, NOW)).isTrue();
        assertThat(MandatePolicy.isActive(active, NOW.plusSeconds(60))).isFalse();
        assertThat(MandatePolicy.isActive(new MandateRecord(active.id(), active.workItemId(),
                "req", active.allowedTools(), active.allowedDocIds(), 3, "REVOKED",
                active.expiresAt()), NOW)).isFalse();

        assertThat(MandatePolicy.allowsTool(active, "search_knowledge")).isTrue();
        assertThat(MandatePolicy.allowsTool(active, "get_document")).isFalse();
        assertThat(MandatePolicy.allowsTopK(active, 3)).isTrue();
        assertThat(MandatePolicy.allowsTopK(active, 4)).isFalse();
        assertThat(MandatePolicy.allowsTopK(active, 0)).isFalse();
        assertThat(MandatePolicy.allowsDocIds(active, List.of(DOC_A))).isTrue();
        assertThat(MandatePolicy.allowsDocIds(active, List.of(DOC_B))).isFalse();
        assertThat(MandatePolicy.allowsDocIds(active, null)).isTrue();
        assertThat(MandatePolicy.allowsDocIds(active, List.of())).isTrue();

        MandateRecord allDocs = new MandateRecord(active.id(), active.workItemId(), "req",
                active.allowedTools(), null, 3, "ACTIVE", active.expiresAt());
        assertThat(MandatePolicy.allowsDocIds(allDocs, null)).isTrue();
        assertThat(MandatePolicy.allowsDocIds(allDocs, List.of(DOC_C))).isTrue();

        MandateRecord noDocs = new MandateRecord(active.id(), active.workItemId(), "req",
                active.allowedTools(), List.of(), 3, "ACTIVE", active.expiresAt());
        assertThat(MandatePolicy.allowsDocIds(noDocs, List.of())).isTrue();
        assertThat(MandatePolicy.allowsDocIds(noDocs, List.of(DOC_A))).isFalse();
    }

    private static ApprovalDecision policy(WorkItemRecord item, ApprovalRequest request) {
        return MandatePolicy.decideApproval(item, request, NOW);
    }

    private static ApprovalRequest requestTtl(int ttlSeconds) {
        return new ApprovalRequest(ttlSeconds, null, true, List.of(DOC_A.toString()), null, null);
    }

    private static WorkItemRecord item(List<UUID> requestedDocIds) {
        return item(requestedDocIds, List.of("search_knowledge", "get_document"));
    }

    private static WorkItemRecord item(List<UUID> requestedDocIds, List<String> tools) {
        return new WorkItemRecord(UUID.randomUUID(), "requester", "purpose", tools,
                requestedDocIds, 5, "PENDING", null, null, null, NOW);
    }

    private static ApiException validation(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected ApiException");
        } catch (ApiException ex) {
            return ex;
        }
    }
}
