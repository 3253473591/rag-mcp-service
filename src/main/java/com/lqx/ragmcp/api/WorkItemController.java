package com.lqx.ragmcp.api;

import com.lqx.ragmcp.api.dto.ApproveWorkItemRequest;
import com.lqx.ragmcp.api.dto.ApproveWorkItemResponse;
import com.lqx.ragmcp.api.dto.CreateWorkItemRequest;
import com.lqx.ragmcp.api.dto.CreateWorkItemResponse;
import com.lqx.ragmcp.api.dto.DenyWorkItemRequest;
import com.lqx.ragmcp.api.dto.DenyWorkItemResponse;
import com.lqx.ragmcp.api.dto.WorkItemResponse;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.governance.AdminAuth;
import com.lqx.ragmcp.governance.WorkItemService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 4 governance HTTP API (docs/02-api-contract.md 2.5-2.8).
 *
 * <p>Approve/deny require {@code X-Admin-Token}. The token is checked before the path is parsed
 * and before any business logic, and failures are 401 {@code ERR-ADMIN_UNAUTHORIZED}.</p>
 */
@RestController
@RequestMapping("/api/v1/work-items")
public class WorkItemController {

    private final WorkItemService workItemService;
    private final AdminAuth adminAuth;

    public WorkItemController(WorkItemService workItemService, AdminAuth adminAuth) {
        this.workItemService = workItemService;
        this.adminAuth = adminAuth;
    }

    @PostMapping
    public ResponseEntity<CreateWorkItemResponse> create(
            @RequestBody(required = false) CreateWorkItemRequest request) {
        CreateWorkItemResponse body = WorkItemApiMapper.toCreateResponse(
                workItemService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{workItemId}")
    public WorkItemResponse get(@PathVariable("workItemId") String workItemId) {
        return WorkItemApiMapper.toWorkItemResponse(
                workItemService.get(parseWorkItemId(workItemId)));
    }

    @PostMapping("/{workItemId}/approve")
    public ApproveWorkItemResponse approve(
            @RequestHeader(value = AdminAuth.HEADER, required = false) String adminToken,
            @PathVariable("workItemId") String workItemId,
            @RequestBody(required = false) ApproveWorkItemRequest request) {
        adminAuth.requireAuthorized(adminToken);
        return WorkItemApiMapper.toApproveResponse(
                workItemService.approve(parseWorkItemId(workItemId), request));
    }

    @PostMapping("/{workItemId}/deny")
    public DenyWorkItemResponse deny(
            @RequestHeader(value = AdminAuth.HEADER, required = false) String adminToken,
            @PathVariable("workItemId") String workItemId,
            @RequestBody(required = false) DenyWorkItemRequest request) {
        adminAuth.requireAuthorized(adminToken);
        String reason = request == null ? null : request.reason();
        return WorkItemApiMapper.toDenyResponse(
                workItemService.deny(parseWorkItemId(workItemId), reason));
    }

    private static UUID parseWorkItemId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.validationField("workItemId", "workItemId 必须是合法 UUID");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.validationField("workItemId", "workItemId 必须是合法 UUID");
        }
    }
}
