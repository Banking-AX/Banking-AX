package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.StartTaskCommand;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.TaskHandler;
import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.core.workflow.WorkItemView;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
public class WorkflowStartService {
    private final WorkflowService workflows;
    private final TaskHandler tasks;
    private final WorkflowRepository workflowRepository;
    private final WorkItemRepository workItemRepository;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    public WorkflowStartService(WorkflowService workflows, TaskHandler tasks,
                                WorkflowRepository workflowRepository, WorkItemRepository workItemRepository,
                                TransactionTemplate transactions, EntityManager entityManager) {
        this.workflows = workflows;
        this.tasks = tasks;
        this.workflowRepository = workflowRepository;
        this.workItemRepository = workItemRepository;
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Transactional
    public StartResult startOrReconnect(StartCommand command) {
        validate(command);
        if (command.chatRoomId() == null) {
            throw new DomainException("INVALID_REQUEST", "A chatRoomId is required for a chat task action.");
        }
        lockOwnedChatRoom(command.customerId(), command.chatRoomId());
        StartResult replay = findReplay(command);
        if (replay != null) {
            return replay;
        }
        var linked = workItemRepository.findLatestLinkedUnfinished(
                command.customerId(), command.chatRoomId(), command.taskCode().name());
        if (linked.isPresent()) {
            return result(linked.get());
        }
        return createAndStart(command);
    }

    private void lockOwnedChatRoom(long customerId, long chatRoomId) {
        try {
            entityManager.createNativeQuery(
                    "select id from chat_room where id=:roomId and customer_id=:customerId for update")
                    .setParameter("roomId", chatRoomId)
                    .setParameter("customerId", customerId)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new DomainException("CHAT_ROOM_NOT_AVAILABLE", "Chat room is not available.");
        }
    }

    public StartResult start(StartCommand command) {
        validate(command);
        StartResult replay = findReplay(command);
        if (replay != null) {
            return replay;
        }

        try {
            return Objects.requireNonNull(transactions.execute(status -> createAndStart(command)));
        } catch (DomainException exception) {
            if (!"DUPLICATE_REQUEST_CONFLICT".equals(exception.code())) {
                throw exception;
            }
            StartResult concurrentReplay = findReplay(command);
            if (concurrentReplay != null) {
                return concurrentReplay;
            }
            throw exception;
        }
    }

    private StartResult createAndStart(StartCommand command) {
        WorkflowService.WorkflowView workflow = workflows.create(
                new WorkflowService.CreateWorkflowCommand(command.customerId(), command.chatRoomId()));
        WorkItemView workItem = tasks.start(new StartTaskCommand(
                command.customerId(), workflow.workflowId(), command.taskCode(), workflow.version(),
                command.requestId()));
        return result(workItem);
    }

    private StartResult findReplay(StartCommand command) {
        return workItemRepository.findByRequestId(command.requestId()).map(item -> {
            WorkflowEntity workflow = workflowRepository.findById(item.workflowId)
                    .orElseThrow(() -> new DomainException(
                            "DUPLICATE_REQUEST_CONFLICT", "requestId points to an unavailable workflow."));
            if (workflow.customerId != command.customerId()
                    || !Objects.equals(workflow.chatRoomId, command.chatRoomId())
                    || item.taskCode != command.taskCode()) {
                throw new DomainException(
                        "DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another workflow start.");
            }
            return new StartResult(workflow.id, item.id, item.taskCode, item.status);
        }).orElse(null);
    }

    private static StartResult result(WorkItemView workItem) {
        return new StartResult(workItem.workflowId(), workItem.workItemId(), workItem.taskCode(), workItem.status());
    }

    private StartResult result(WorkItemEntity workItem) {
        WorkflowEntity workflow = workflowRepository.findById(workItem.workflowId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Workflow was not found."));
        return new StartResult(workflow.id, workItem.id, workItem.taskCode, workItem.status);
    }

    private static void validate(StartCommand command) {
        if (command == null || command.customerId() <= 0 || command.taskCode() == null
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 90) {
            throw new DomainException("INVALID_REQUEST", "A valid requestId, customerId, and taskCode are required.");
        }
    }

    public record StartCommand(String requestId, long customerId, Long chatRoomId, TaskCode taskCode) {
        public StartCommand(String requestId, long customerId, TaskCode taskCode) {
            this(requestId, customerId, null, taskCode);
        }
    }

    public record StartResult(long workflowId, long workItemId, TaskCode taskCode, WorkItemStatus status) { }
}
