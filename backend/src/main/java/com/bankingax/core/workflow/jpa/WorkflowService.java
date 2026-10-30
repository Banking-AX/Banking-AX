package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.ActiveWorkItemValidator;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.WorkItemStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
public class WorkflowService implements ActiveWorkItemValidator {
    private static final String WAITING_CONTINUE = "WAITING_CONTINUE";

    private final WorkflowRepository workflows;
    private final WorkItemRepository workItems;

    public WorkflowService(WorkflowRepository workflows, WorkItemRepository workItems) {
        this.workflows = workflows;
        this.workItems = workItems;
    }

    @Override
    @Transactional(readOnly = true)
    public void validate(Long workItemId, long customerId, TaskCode expectedTaskCode) {
        if (workItemId == null) {
            throw unavailableWorkItem();
        }
        WorkItemEntity workItem = workItems.findById(workItemId).orElseThrow(WorkflowService::unavailableWorkItem);
        WorkflowEntity workflow = workflows.findById(workItem.workflowId)
                .orElseThrow(WorkflowService::unavailableWorkItem);
        if (workflow.customerId != customerId
                || workItem.taskCode != expectedTaskCode
                || workItem.status != WorkItemStatus.ACTIVE) {
            throw unavailableWorkItem();
        }
    }

    private static DomainException unavailableWorkItem() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "An active WorkItem is not available for this task.");
    }

    @Transactional
    public WorkflowView create(CreateWorkflowCommand command) {
        if (workflows.existsActiveCustomerById(command.customerId()) == 0) {
            throw new DomainException("CUSTOMER_NOT_FOUND", "Active customer was not found.");
        }
        if (command.chatRoomId() != null) {
            validateChatRoom(command.chatRoomId(), command.customerId());
        }

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.customerId = command.customerId();
        workflow.chatRoomId = command.chatRoomId();
        workflow.status = WAITING_CONTINUE;
        workflow = workflows.saveAndFlush(workflow);
        return new WorkflowView(workflow.id, workflow.customerId, workflow.chatRoomId, workflow.status, workflow.version);
    }

    private void validateChatRoom(long chatRoomId, long customerId) {
        if (workflows.existsChatRoomById(chatRoomId) == 0) {
            throw new DomainException("CHAT_ROOM_NOT_FOUND", "Chat room was not found.");
        }
        if (workflows.existsChatRoomOwnedByCustomerId(chatRoomId, customerId) == 0) {
            throw new DomainException("FORBIDDEN", "Chat room ownership does not match.");
        }
    }

    public record CreateWorkflowCommand(long customerId, Long chatRoomId) { }

    public record WorkflowView(long workflowId, long customerId, Long chatRoomId, String status, int version) { }
}
