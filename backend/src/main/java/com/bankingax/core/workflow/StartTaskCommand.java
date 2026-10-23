package com.bankingax.core.workflow;

public record StartTaskCommand(long actorId, long workflowId, TaskCode taskCode, int expectedWorkflowVersion,
                               String requestId) { }
