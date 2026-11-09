package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.StartTaskCommand;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.TaskHandler;
import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.core.workflow.WorkItemView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
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
        if (command.taskCode() == TaskCode.REPAYMENT_ORDER_CHANGE) {
            B01PrepareResult prepared = prepareB01(new B01PrepareCommand(
                    command.requestId(), command.customerId(), command.chatRoomId(),
                    B01PrepareMode.DISCOVER, null));
            return switch (prepared.outcome()) {
                case CREATED, REQUEST_REPLAYED, SAME_CHAT_REUSED, EXISTING_RECONNECTED -> prepared.startResult();
                case RECONNECT_CONFIRMATION_REQUIRED -> throw new DomainException(
                        "B01_RECONNECT_CONFIRMATION_REQUIRED",
                        "An unfinished B-01 work item exists in another chat room.");
                case ACTIVE_WORK_ITEM_CONFLICT -> throw new DomainException(
                        "WORKFLOW_ACTIVE_EXISTS", "An active B-01 work item already exists.");
            };
        }
        return executeWithRecovery(command, true);
    }

    public B01PrepareResult prepareB01(B01PrepareCommand command) {
        return prepareB01(command, false);
    }

    public B01PrepareResult prepareB01(B01PrepareCommand command, boolean includeCandidates) {
        validateB01(command);
        return Objects.requireNonNull(transactions.execute(status -> {
            B01PrepareResult result = prepareB01InTransaction(command);
            if (!includeCandidates || result.outcome() != B01PrepareOutcome.RECONNECT_CONFIRMATION_REQUIRED) {
                return result;
            }
            return new B01PrepareResult(result.outcome(), result.workItem(),
                    b01Candidates(command.customerId(), command.chatRoomId()));
        }));
    }

    void lockCustomerAndOwnedChat(long customerId, long chatRoomId) {
        workflowRepository.lockActiveCustomerById(customerId)
                .orElseThrow(() -> new DomainException("FORBIDDEN", "Customer is not active."));
        lockOwnedChatRoom(customerId, chatRoomId);
    }

    List<B01Candidate> b01Candidates(long customerId, long currentChatRoomId) {
        return workItemRepository.findAllUnfinishedInAnotherChat(
                        customerId, currentChatRoomId, TaskCode.REPAYMENT_ORDER_CHANGE.name()).stream()
                .map(item -> {
                    WorkflowEntity workflow = workflowRepository.findById(item.workflowId)
                            .orElseThrow(WorkflowStartService::unavailableB01);
                    Long originalChatRoomId = workflow.chatRoomId;
                    boolean available = originalChatRoomId != null
                            && workflowRepository.existsChatRoomOwnedByCustomerId(
                                    originalChatRoomId, customerId) == 1;
                    return new B01Candidate(item.id, item.status, originalChatRoomId, available);
                })
                .toList();
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
        boolean reconnectUnfinished = command.taskCode() != TaskCode.LOAN_INQUIRY;
        if (reconnectUnfinished && reconnectChatTask) {
            var linked = workItemRepository.findLatestLinkedUnfinished(
                    command.customerId(), command.chatRoomId(), command.taskCode().name());
            if (linked.isPresent()) {
                return result(linked.get());
            }
        }
        if (reconnectUnfinished) {
            var active = workItemRepository.findActiveForCustomerAndTaskForUpdate(
                    command.customerId(), command.taskCode(), WorkItemStatus.ACTIVE);
            if (!active.isEmpty()) {
                return result(active.get(0));
            }
        }
        WorkflowService.WorkflowView workflow = workflows.create(
                new WorkflowService.CreateWorkflowCommand(command.customerId(), command.chatRoomId()));
        WorkItemView workItem = tasks.start(new StartTaskCommand(
                command.customerId(), workflow.workflowId(), command.taskCode(), workflow.version(),
                command.requestId()));
        return result(workItem);
    }

    private B01PrepareResult prepareB01InTransaction(B01PrepareCommand command) {
        workflowRepository.lockActiveCustomerById(command.customerId())
                .orElseThrow(() -> new DomainException("FORBIDDEN", "Customer is not active."));
        lockOwnedChatRoom(command.customerId(), command.chatRoomId());

        StartCommand startCommand = new StartCommand(
                command.requestId(), command.customerId(), command.chatRoomId(),
                TaskCode.REPAYMENT_ORDER_CHANGE);
        StartResult replay = findReplay(startCommand);
        if (replay != null) {
            return new B01PrepareResult(B01PrepareOutcome.REQUEST_REPLAYED, replay);
        }

        if (command.mode() == B01PrepareMode.CONTINUE_EXISTING) {
            WorkItemEntity existing = findOwnedUnfinishedB01(command.customerId(), command.workItemId());
            return new B01PrepareResult(B01PrepareOutcome.EXISTING_RECONNECTED, result(existing));
        }

        var sameChat = workItemRepository.findLatestLinkedUnfinished(
                command.customerId(), command.chatRoomId(), TaskCode.REPAYMENT_ORDER_CHANGE.name());
        if (sameChat.isPresent()) {
            return new B01PrepareResult(B01PrepareOutcome.SAME_CHAT_REUSED, result(sameChat.get()));
        }

        var otherChat = workItemRepository.findLatestUnfinishedInAnotherChat(
                command.customerId(), command.chatRoomId(), TaskCode.REPAYMENT_ORDER_CHANGE.name());
        if (otherChat.isPresent()) {
            if (command.mode() == B01PrepareMode.DISCOVER) {
                return new B01PrepareResult(
                        B01PrepareOutcome.RECONNECT_CONFIRMATION_REQUIRED, result(otherChat.get()));
            }
            if (otherChat.get().status == WorkItemStatus.ACTIVE) {
                return new B01PrepareResult(
                        B01PrepareOutcome.ACTIVE_WORK_ITEM_CONFLICT, result(otherChat.get()));
            }
        }

        WorkflowService.WorkflowView workflow = workflows.create(
                new WorkflowService.CreateWorkflowCommand(command.customerId(), command.chatRoomId()));
        WorkItemView workItem = tasks.start(new StartTaskCommand(
                command.customerId(), workflow.workflowId(), TaskCode.REPAYMENT_ORDER_CHANGE,
                workflow.version(), command.requestId()));
        return new B01PrepareResult(B01PrepareOutcome.CREATED, result(workItem));
    }

    private WorkItemEntity findOwnedUnfinishedB01(long customerId, Long workItemId) {
        if (workItemId == null || workItemId <= 0) {
            throw new DomainException("INVALID_REQUEST", "A workItemId is required to continue B-01.");
        }
        WorkItemEntity item = workItemRepository.findForUpdate(workItemId)
                .orElseThrow(WorkflowStartService::unavailableB01);
        WorkflowEntity workflow = workflowRepository.findById(item.workflowId)
                .orElseThrow(WorkflowStartService::unavailableB01);
        if (workflow.customerId != customerId
                || item.taskCode != TaskCode.REPAYMENT_ORDER_CHANGE
                || (item.status != WorkItemStatus.ACTIVE && item.status != WorkItemStatus.ON_HOLD)) {
            throw unavailableB01();
        }
        return item;
    }

    private static DomainException unavailableB01() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
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

    private static void validateB01(B01PrepareCommand command) {
        if (command == null || command.customerId() <= 0 || command.chatRoomId() <= 0
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 90 || command.mode() == null) {
            throw new DomainException(
                    "INVALID_REQUEST", "A valid requestId, customerId, chatRoomId, and mode are required.");
        }
        if (command.mode() != B01PrepareMode.CONTINUE_EXISTING && command.workItemId() != null) {
            throw new DomainException("INVALID_REQUEST", "workItemId is only allowed when continuing B-01.");
        }
    }

    public record StartCommand(String requestId, long customerId, Long chatRoomId, TaskCode taskCode) {
        public StartCommand(String requestId, long customerId, TaskCode taskCode) {
            this(requestId, customerId, null, taskCode);
        }
    }

    public record StartResult(long workflowId, long workItemId, TaskCode taskCode, WorkItemStatus status) { }

    public enum B01PrepareMode {
        DISCOVER, CONTINUE_EXISTING, START_NEW
    }

    public enum B01PrepareOutcome {
        CREATED,
        REQUEST_REPLAYED,
        SAME_CHAT_REUSED,
        RECONNECT_CONFIRMATION_REQUIRED,
        EXISTING_RECONNECTED,
        ACTIVE_WORK_ITEM_CONFLICT
    }

    public record B01PrepareCommand(String requestId, long customerId, long chatRoomId,
                                    B01PrepareMode mode, Long workItemId) { }

    public record B01Candidate(long workItemId, WorkItemStatus status, Long originalChatRoomId,
                               boolean originalChatRoomAvailable) { }

    public record B01PrepareResult(B01PrepareOutcome outcome, StartResult workItem,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<B01Candidate> candidates) {
        public B01PrepareResult(B01PrepareOutcome outcome, StartResult workItem) {
            this(outcome, workItem, List.of());
        }

        StartResult startResult() {
            return workItem;
        }
    }
}
