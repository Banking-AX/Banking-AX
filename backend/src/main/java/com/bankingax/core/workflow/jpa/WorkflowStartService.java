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

    public StartResult startOrReconnect(StartCommand command) {
        validate(command);
        if (command.chatRoomId() == null) {
            throw new DomainException("INVALID_REQUEST", "A chatRoomId is required for a chat task action.");
        }
        return executeWithRecovery(command, true);
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
        return executeWithRecovery(command, false);
    }

    private StartResult executeWithRecovery(StartCommand command, boolean reconnectChatTask) {
        try {
            return Objects.requireNonNull(transactions.execute(
                    status -> resumeOrCreate(command, reconnectChatTask)));
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

    private StartResult resumeOrCreate(StartCommand command, boolean reconnectChatTask) {
        // Locking the customer row serializes distinct requestIds from rapid
        // repeated card clicks before looking up an ACTIVE item. This is always
        // acquired before the chat-room lock, preventing inverse lock ordering.
        workflowRepository.lockActiveCustomerById(command.customerId())
                .orElseThrow(() -> new DomainException("FORBIDDEN", "Customer is not active."));
        if (reconnectChatTask) {
            lockOwnedChatRoom(command.customerId(), command.chatRoomId());
        }
        StartResult replay = findReplay(command);
        if (replay != null) {
            return replay;
        }
        if (reconnectChatTask) {
            var linked = workItemRepository.findLatestLinkedUnfinished(
                    command.customerId(), command.chatRoomId(), command.taskCode().name());
            if (linked.isPresent()) {
                return result(linked.get());
            }
        }
        var active = workItemRepository.findResumableActiveForCustomerAndTaskForUpdate(
                command.customerId(), command.taskCode().name(), WorkItemStatus.ACTIVE.name());
        if (!active.isEmpty()) {
            return result(active.get(0));
        }
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

    private static StartResult result(WorkItemEntity item) {
        return new StartResult(item.workflowId, item.id, item.taskCode, item.status);
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
