package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
public class WorkflowService {
    private static final String WAITING_CONTINUE = "WAITING_CONTINUE";

    private final WorkflowRepository workflows;

    public WorkflowService(WorkflowRepository workflows) {
        this.workflows = workflows;
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
