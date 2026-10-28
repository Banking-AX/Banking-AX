package com.bankingax.core.workflow;

import com.bankingax.core.api.DomainException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Phase 0 fake only. The JPA implementation will enforce the DDL's active-work-item constraint. */
@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "inmemory", matchIfMissing = true)
public class InMemoryTaskHandler implements TaskHandler {
    private final AtomicLong ids = new AtomicLong(1000);
    private final Map<Long, MutableWorkItem> items = new HashMap<>();
    private final Map<Long, Long> activeByWorkflow = new HashMap<>();

    @Override public synchronized WorkItemView start(StartTaskCommand command) {
        if (activeByWorkflow.containsKey(command.workflowId()))
            throw new DomainException("WORKFLOW_ACTIVE_EXISTS", "An active work item already exists.");
        long id = ids.incrementAndGet();
        MutableWorkItem item = new MutableWorkItem(id, command.actorId(), command.workflowId(), command.taskCode(),
                WorkItemStatus.ACTIVE, 0, null);
        items.put(id, item); activeByWorkflow.put(command.workflowId(), id);
        return item.view();
    }
    @Override public synchronized WorkItemView resume(long actorId, long workItemId, int version) {
        MutableWorkItem item = owned(actorId, workItemId, version);
        if (item.status != WorkItemStatus.ON_HOLD) throw new DomainException("INVALID_STATE", "Only held work can resume.");
        if (activeByWorkflow.containsKey(item.workflowId)) throw new DomainException("WORKFLOW_ACTIVE_EXISTS", "An active work item already exists.");
        item.status = WorkItemStatus.ACTIVE; item.version++; activeByWorkflow.put(item.workflowId, item.id); return item.view();
    }
    @Override public synchronized WorkItemView hold(long actorId, long workItemId, int version, String resumeStep) {
        MutableWorkItem item = owned(actorId, workItemId, version);
        if (item.status != WorkItemStatus.ACTIVE) throw new DomainException("INVALID_STATE", "Only active work can be held.");
        item.status = WorkItemStatus.ON_HOLD; item.resumeStep = resumeStep; item.version++; activeByWorkflow.remove(item.workflowId); return item.view();
    }
    @Override public synchronized WorkItemView complete(long actorId, long workItemId, int version) {
        MutableWorkItem item = owned(actorId, workItemId, version);
        if (item.status != WorkItemStatus.ACTIVE) throw new DomainException("INVALID_STATE", "Only active work can complete.");
        item.status = WorkItemStatus.DONE; item.version++; activeByWorkflow.remove(item.workflowId); return item.view();
    }
    private MutableWorkItem owned(long actorId, long id, int version) {
        MutableWorkItem item = items.get(id);
        if (item == null) throw new DomainException("NOT_FOUND", "Work item was not found.");
        if (item.actorId != actorId) throw new DomainException("FORBIDDEN", "Work item ownership does not match.");
        if (item.version != version) throw new DomainException("VERSION_CONFLICT", "Work item version is stale.", true);
        return item;
    }
    private static final class MutableWorkItem {
        long id, actorId, workflowId; TaskCode taskCode; WorkItemStatus status; int version; String resumeStep;
        MutableWorkItem(long id, long actorId, long workflowId, TaskCode taskCode, WorkItemStatus status, int version, String resumeStep) {
            this.id=id; this.actorId=actorId; this.workflowId=workflowId; this.taskCode=taskCode; this.status=status; this.version=version; this.resumeStep=resumeStep;
        }
        WorkItemView view() { return new WorkItemView(id, workflowId, taskCode, status, version, resumeStep); }
    }
}
