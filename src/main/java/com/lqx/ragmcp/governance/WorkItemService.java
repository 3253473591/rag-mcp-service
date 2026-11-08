package com.lqx.ragmcp.governance;

import com.lqx.ragmcp.api.dto.ApproveWorkItemRequest;
import com.lqx.ragmcp.api.dto.CreateWorkItemRequest;
import com.lqx.ragmcp.api.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 4 governance orchestration: create/read WorkItems and atomically approve/deny them.
 *
 * <p>Approve/deny are transactional: the WorkItem status change and the mandate insert either
 * both commit or both roll back. Repeated or stale decisions are rejected with 409.</p>
 */
@Service
public class WorkItemService {

    public static final int MAX_PURPOSE_CHARS = 500;
    public static final int MAX_TOOLS = 5;
    public static final int MAX_DOC_IDS = 50;

    private final WorkItemRepository repository;

    public WorkItemService(WorkItemRepository repository) {
        this.repository = repository;
    }

    public WorkItemRecord create(CreateWorkItemRequest request) {
        if (request == null) {
            throw ApiException.validation("请求体不能为空");
        }

        String requester = requiredText(request.requester(), "requester", "requester 不能为空");
        String purpose = requiredText(request.purpose(), "purpose", "purpose 不能为空");
        if (purpose.length() > MAX_PURPOSE_CHARS) {
            throw ApiException.validationField("purpose", "purpose 不能超过 500 字符");
        }

        List<String> requestedTools = parseRequestedTools(request.requestedTools());
        List<UUID> requestedDocIds = parseRequestedDocIds(request.requestedDocIds());

        int requestedTopK = request.requestedTopK() == null
                ? MandatePolicy.DEFAULT_TOPK
                : request.requestedTopK();
        if (requestedTopK < MandatePolicy.MIN_TOPK || requestedTopK > MandatePolicy.MAX_TOPK) {
            throw ApiException.validationField("requestedTopK", "requestedTopK 必须在 1~20 之间");
        }

        WorkItemRecord pending = new WorkItemRecord(
                UUID.randomUUID(),
                requester,
                purpose,
                requestedTools,
                requestedDocIds,
                requestedTopK,
                WorkItemRecord.STATUS_PENDING,
                null,
                null,
                null,
                null);
        return repository.insert(pending);
    }

    public WorkItemDetails get(UUID workItemId) {
        WorkItemRecord workItem = findWorkItem(workItemId);
        MandateRecord mandate = WorkItemRecord.STATUS_APPROVED.equals(workItem.status())
                ? repository.findMandateByWorkItemId(workItemId).orElse(null)
                : null;
        return new WorkItemDetails(workItem, mandate);
    }

    @Transactional
    public ApprovalResult approve(UUID workItemId, ApproveWorkItemRequest request) {
        WorkItemRecord workItem = findWorkItem(workItemId);
        requirePending(workItem);

        OffsetDateTime now = OffsetDateTime.now();
        ApprovalDecision decision = MandatePolicy.decideApproval(workItem, toApprovalRequest(request), now);

        int updated = repository.markApproved(
                workItemId, AdminAuth.PRINCIPAL, now, decision.reason());
        if (updated != 1) {
            throw invalidState(workItem);
        }

        MandateRecord mandate = new MandateRecord(
                UUID.randomUUID(),
                workItemId,
                workItem.requester(),
                decision.allowedTools(),
                decision.allowedDocIds(),
                decision.maxTopK(),
                MandateRecord.STATUS_ACTIVE,
                decision.expiresAt());
        try {
            repository.insertMandate(mandate);
        } catch (DuplicateKeyException ex) {
            // Unique (work_item_id) guard. Rethrowing a RuntimeException rolls back markApproved.
            throw ApiException.approvalInvalid("该 WorkItem 已存在 mandate，不能重复审批");
        }

        WorkItemRecord approved = workItem.withDecision(
                WorkItemRecord.STATUS_APPROVED,
                AdminAuth.PRINCIPAL,
                now,
                decision.reason());
        return new ApprovalResult(approved, mandate);
    }

    @Transactional
    public WorkItemRecord deny(UUID workItemId, String reason) {
        WorkItemRecord workItem = findWorkItem(workItemId);
        requirePending(workItem);

        OffsetDateTime now = OffsetDateTime.now();
        String normalizedReason = normalizeReason(reason);
        int updated = repository.markDenied(
                workItemId, AdminAuth.PRINCIPAL, now, normalizedReason);
        if (updated != 1) {
            throw invalidState(workItem);
        }
        return workItem.withDecision(
                WorkItemRecord.STATUS_DENIED,
                AdminAuth.PRINCIPAL,
                now,
                normalizedReason);
    }

    private WorkItemRecord findWorkItem(UUID workItemId) {
        return repository.findById(workItemId)
                .orElseThrow(() -> ApiException.workItemNotFound(
                        workItemId == null ? null : workItemId.toString()));
    }

    private static void requirePending(WorkItemRecord workItem) {
        if (!workItem.isPending()) {
            throw invalidState(workItem);
        }
    }

    private static ApiException invalidState(WorkItemRecord workItem) {
        return ApiException.approvalInvalid(
                "WorkItem 当前状态为 " + workItem.status() + "，不能重复审批");
    }

    private static ApprovalRequest toApprovalRequest(ApproveWorkItemRequest request) {
        if (request == null) {
            return new ApprovalRequest(null, null, false, null, null, null);
        }
        return new ApprovalRequest(
                request.getTtlSeconds(),
                request.getAllowedTools(),
                request.isAllowedDocIdsProvided(),
                request.getAllowedDocIds(),
                request.getMaxTopK(),
                request.getReason());
    }

    private static String requiredText(String raw, String field, String message) {
        if (raw == null) {
            throw ApiException.validationField(field, message);
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw ApiException.validationField(field, message);
        }
        return value;
    }

    private static List<String> parseRequestedTools(List<String> rawTools) {
        if (rawTools == null || rawTools.isEmpty()) {
            throw ApiException.validationField("requestedTools", "requestedTools 不能为空");
        }
        if (rawTools.size() > MAX_TOOLS) {
            throw ApiException.validationField("requestedTools", "requestedTools 最多 5 个");
        }
        Set<String> tools = new LinkedHashSet<>();
        for (String raw : rawTools) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.validationField("requestedTools",
                        "requestedTools 只能包含 search_knowledge / get_document");
            }
            String tool = raw.trim();
            if (!MandatePolicy.V1_TOOLS.contains(tool)) {
                throw ApiException.validationField("requestedTools",
                        "requestedTools 只能包含 search_knowledge / get_document");
            }
            tools.add(tool);
        }
        return List.copyOf(tools);
    }

    private static List<UUID> parseRequestedDocIds(List<String> rawDocIds) {
        if (rawDocIds == null) {
            return null;
        }
        if (rawDocIds.size() > MAX_DOC_IDS) {
            throw ApiException.validationField("requestedDocIds", "requestedDocIds 最多 50 个");
        }
        Set<UUID> docIds = new LinkedHashSet<>();
        for (String raw : rawDocIds) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.validationField("requestedDocIds",
                        "requestedDocIds 必须都是合法 UUID");
            }
            try {
                docIds.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ex) {
                throw ApiException.validationField("requestedDocIds",
                        "requestedDocIds 必须都是合法 UUID");
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
}
