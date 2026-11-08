package com.lqx.ragmcp.governance;

/** Result of a successful approve: the updated WorkItem and its new mandate. */
public record ApprovalResult(WorkItemRecord workItem, MandateRecord mandate) {
}
