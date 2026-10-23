package com.bankingax.core.workflow;

public interface TaskHandler {
    WorkItemView start(StartTaskCommand command);
    WorkItemView resume(long actorId, long workItemId, int expectedVersion);
    WorkItemView hold(long actorId, long workItemId, int expectedVersion, String resumeStep);
    WorkItemView complete(long actorId, long workItemId, int expectedVersion);
}
