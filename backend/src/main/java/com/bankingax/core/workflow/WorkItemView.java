package com.bankingax.core.workflow;

public record WorkItemView(long workItemId, long workflowId, TaskCode taskCode, WorkItemStatus status,
                           int version, String resumeStep) { }
