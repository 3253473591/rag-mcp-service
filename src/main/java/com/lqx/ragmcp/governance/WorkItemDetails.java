package com.lqx.ragmcp.governance;

/** WorkItem plus its mandate (if any) for GET /api/v1/work-items/{id}. */
public record WorkItemDetails(WorkItemRecord workItem, MandateRecord mandate) {
}
