package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.StartTaskCommand;
import com.bankingax.core.workflow.TaskHandler;
import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.core.workflow.WorkItemView;
import java.time.LocalDateTime;
import java.util.EnumSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
public class JpaTaskHandler implements TaskHandler {
    private static final String RUNNING = "RUNNING";
    private static final String WAITING_CONTINUE = "WAITING_CONTINUE";
    private static final String COMPLETED = "COMPLETED";
    private static final EnumSet<WorkItemStatus> UNFINISHED = EnumSet.of(
            WorkItemStatus.PLANNED, WorkItemStatus.ACTIVE, WorkItemStatus.ON_HOLD);

    private final WorkflowRepository workflows;
    private final WorkItemRepository workItems;

    public JpaTaskHandler(WorkflowRepository workflows, WorkItemRepository workItems) {
        this.workflows = workflows;
        this.workItems = workItems;
    }

    @Override
    @Transactional
    public WorkItemView start(StartTaskCommand command) {
        validate(command);

        WorkflowEntity workflow = workflows.findForUpdate(command.workflowId())
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Workflow was not found."));
        if (workflow.customerId != command.actorId()) {
            throw new DomainException("FORBIDDEN", "Workflow ownership does not match.");
        }

        var replay = workItems.findByRequestId(command.requestId());
        if (replay.isPresent()) {
            return replay(replay.get(), command);
        }
        if (workflow.version != command.expectedWorkflowVersion()) {
            throw new DomainException("VERSION_CONFLICT", "Workflow version is stale.", true);
        }
        if (workItems.existsByWorkflowIdAndStatus(workflow.id, WorkItemStatus.ACTIVE)) {
            throw new DomainException("WORKFLOW_ACTIVE_EXISTS", "An active work item already exists.");
        }

        WorkItemEntity item = new WorkItemEntity();
        item.workflowId = workflow.id;
        item.taskCode = command.taskCode();
        item.sequenceNo = workItems.findMaxSequenceNoByWorkflowId(workflow.id) + 1;
        item.status = WorkItemStatus.ACTIVE;
        item.requestId = command.requestId();

        workflow.status = RUNNING;
        workflow.updatedAt = LocalDateTime.now();
        try {
            item = workItems.saveAndFlush(item);
            workflows.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "Workflow start conflicted with another request.", true);
        }
        return view(item);
    }

    @Override
    @Transactional
    public WorkItemView resume(long actorId, long workItemId, int expectedVersion) {
        LockedWork locked = ownedForUpdate(actorId, workItemId);
        WorkItemEntity item = locked.item();
        verifyVersion(item, expectedVersion);
        if (item.status != WorkItemStatus.ON_HOLD) {
            throw new DomainException("INVALID_STATE", "Only held work can resume.");
        }
        if (workItems.existsByWorkflowIdAndStatus(item.workflowId, WorkItemStatus.ACTIVE)) {
            throw new DomainException("WORKFLOW_ACTIVE_EXISTS", "An active work item already exists.");
        }
        item.status = WorkItemStatus.ACTIVE;
        touch(item, locked.workflow(), RUNNING);
        try {
            workItems.flush();
            workflows.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw new DomainException("WORKFLOW_ACTIVE_EXISTS", "An active work item already exists.", true);
        }
        return view(item);
    }

    @Override
    @Transactional
    public WorkItemView hold(long actorId, long workItemId, int expectedVersion, String resumeStep) {
        LockedWork locked = ownedForUpdate(actorId, workItemId);
        WorkItemEntity item = locked.item();
        verifyVersion(item, expectedVersion);
        if (item.status != WorkItemStatus.ACTIVE) {
            throw new DomainException("INVALID_STATE", "Only active work can be held.");
        }
        item.status = WorkItemStatus.ON_HOLD;
        item.resumeStep = resumeStep;
        touch(item, locked.workflow(), WAITING_CONTINUE);
        workItems.flush();
        workflows.flush();
        return view(item);
    }

    @Override
    @Transactional
    public WorkItemView complete(long actorId, long workItemId, int expectedVersion) {
        LockedWork locked = ownedForUpdate(actorId, workItemId);
        WorkItemEntity item = locked.item();
        verifyVersion(item, expectedVersion);
        if (item.status != WorkItemStatus.ACTIVE) {
            throw new DomainException("INVALID_STATE", "Only active work can complete.");
        }
        item.status = WorkItemStatus.DONE;
        item.lastActivityAt = LocalDateTime.now();
        workItems.flush();
        String workflowStatus = workItems.existsByWorkflowIdAndStatusIn(item.workflowId, UNFINISHED)
                ? WAITING_CONTINUE : COMPLETED;
        touchWorkflow(locked.workflow(), workflowStatus, item.lastActivityAt);
        workflows.flush();
        return view(item);
    }

    private static void validate(StartTaskCommand command) {
        if (command == null || command.taskCode() == null || command.requestId() == null || command.requestId().isBlank()) {
            throw new DomainException("INVALID_REQUEST", "taskCode and requestId are required.");
        }
    }

    private static WorkItemView replay(WorkItemEntity existing, StartTaskCommand command) {
        if (existing.workflowId != command.workflowId() || existing.taskCode != command.taskCode()) {
            throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another work item.");
        }
        return view(existing);
    }

    private LockedWork ownedForUpdate(long actorId, long workItemId) {
        WorkItemEntity candidate = workItems.findById(workItemId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Work item was not found."));
        WorkflowEntity workflow = workflows.findForUpdate(candidate.workflowId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Workflow was not found."));
        if (workflow.customerId != actorId) {
            throw new DomainException("FORBIDDEN", "Work item ownership does not match.");
        }
        WorkItemEntity item = workItems.findForUpdateByIdAndWorkflowId(workItemId, workflow.id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Work item was not found."));
        return new LockedWork(workflow, item);
    }

    private static void verifyVersion(WorkItemEntity item, int expectedVersion) {
        if (item.version != expectedVersion) {
            throw new DomainException("VERSION_CONFLICT", "Work item version is stale.", true);
        }
    }

    private static void touch(WorkItemEntity item, WorkflowEntity workflow, String workflowStatus) {
        LocalDateTime now = LocalDateTime.now();
        item.lastActivityAt = now;
        touchWorkflow(workflow, workflowStatus, now);
    }

    private static void touchWorkflow(WorkflowEntity workflow, String status, LocalDateTime now) {
        workflow.status = status;
        workflow.updatedAt = now;
    }

    private static WorkItemView view(WorkItemEntity item) {
        return new WorkItemView(item.id, item.workflowId, item.taskCode, item.status, item.version, item.resumeStep);
    }

    private record LockedWork(WorkflowEntity workflow, WorkItemEntity item) { }
}
