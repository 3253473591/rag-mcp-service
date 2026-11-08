package com.lqx.ragmcp.api;

import com.lqx.ragmcp.api.dto.ApproveWorkItemResponse;
import com.lqx.ragmcp.api.dto.CreateWorkItemResponse;
import com.lqx.ragmcp.api.dto.DenyWorkItemResponse;
import com.lqx.ragmcp.api.dto.MandateResponse;
import com.lqx.ragmcp.api.dto.WorkItemResponse;
import com.lqx.ragmcp.governance.ApprovalResult;
import com.lqx.ragmcp.governance.MandateRecord;
import com.lqx.ragmcp.governance.WorkItemDetails;
import com.lqx.ragmcp.governance.WorkItemRecord;

import java.util.List;
import java.util.UUID;

/** Maps governance domain records to Phase 4 response bodies. */
public final class WorkItemApiMapper {

    private WorkItemApiMapper() {
    }

    public static CreateWorkItemResponse toCreateResponse(WorkItemRecord workItem) {
        return new CreateWorkItemResponse(
                workItem.id().toString(),
                workItem.status(),
                workItem.createdAt());
    }

    public static WorkItemResponse toWorkItemResponse(WorkItemDetails details) {
        WorkItemRecord workItem = details.workItem();
        return new WorkItemResponse(
                workItem.id().toString(),
                workItem.requester(),
                workItem.purpose(),
                workItem.requestedTools(),
                uuidList(workItem.requestedDocIds()),
                workItem.requestedTopK(),
                workItem.status(),
                workItem.decidedBy(),
                workItem.decidedAt(),
                workItem.decisionReason(),
                workItem.createdAt(),
                toMandateResponse(details.mandate()));
    }

    public static ApproveWorkItemResponse toApproveResponse(ApprovalResult result) {
        return new ApproveWorkItemResponse(
                result.workItem().id().toString(),
                result.workItem().status(),
                toMandateResponse(result.mandate()));
    }

    public static DenyWorkItemResponse toDenyResponse(WorkItemRecord workItem) {
        return new DenyWorkItemResponse(
                workItem.id().toString(),
                workItem.status(),
                workItem.decidedAt());
    }

    public static MandateResponse toMandateResponse(MandateRecord mandate) {
        if (mandate == null) {
            return null;
        }
        return new MandateResponse(
                mandate.id().toString(),
                mandate.allowedTools(),
                uuidList(mandate.allowedDocIds()),
                mandate.maxTopK(),
                mandate.expiresAt());
    }

    private static List<String> uuidList(List<UUID> ids) {
        return ids == null ? null : ids.stream().map(UUID::toString).toList();
    }
}
