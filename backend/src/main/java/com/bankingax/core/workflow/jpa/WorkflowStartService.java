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

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
public class WorkflowStartService {
    private final WorkflowService workflows;
    private final TaskHandler tasks;
    private final WorkflowRepository workflowRepository;
    private final WorkItemRepository workItemRepository;
    private final TransactionTemplate transactions;

    public WorkflowStartService(WorkflowService workflows, TaskHandler tasks,
                                WorkflowRepository workflowRepository, WorkItemRepository workItemRepository,
                                TransactionTemplate transactions) {
        this.workflows = workflows;
        this.tasks = tasks;
        this.workflowRepository = workflowRepository;
        this.workItemRepository = workItemRepository;
        this.transactions = transactions;
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
                new WorkflowService.CreateWorkflowCommand(command.customerId(), null));
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
            if (workflow.customerId != command.customerId() || item.taskCode != command.taskCode()) {
                throw new DomainException(
                        "DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another workflow start.");
            }
            return new StartResult(workflow.id, item.id, item.taskCode, item.status);
        }).orElse(null);
    }

    private static StartResult result(WorkItemView workItem) {
        return new StartResult(workItem.workflowId(), workItem.workItemId(), workItem.taskCode(), workItem.status());
    }

    private static void validate(StartCommand command) {
        if (command == null || command.customerId() <= 0 || command.taskCode() == null
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 90) {
            throw new DomainException("INVALID_REQUEST", "A valid requestId, customerId, and taskCode are required.");
        }
    }

    public record StartCommand(String requestId, long customerId, TaskCode taskCode) { }

    public record StartResult(long workflowId, long workItemId, TaskCode taskCode, WorkItemStatus status) { }
}
