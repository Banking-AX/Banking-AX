package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.LoanInquiryExecutor;
import com.bankingax.core.tool.LoanInquiryToolRequest;
import com.bankingax.core.tool.LoanInquiryToolResponse;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.TaskHandler;
import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.task.a02.LoanQueryPort;
import com.bankingax.task.a02.LoanSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
class JpaLoanInquiryExecutor implements LoanInquiryExecutor {
    private static final String RESULT_TYPE = "LOAN_INQUIRY";

    private final WorkflowRepository workflows;
    private final WorkItemRepository workItems;
    private final TaskResultRepository results;
    private final LoanQueryPort loans;
    private final TaskHandler tasks;
    private final ObjectMapper json;

    JpaLoanInquiryExecutor(WorkflowRepository workflows, WorkItemRepository workItems,
                           TaskResultRepository results, LoanQueryPort loans,
                           TaskHandler tasks, ObjectMapper json) {
        this.workflows = workflows;
        this.workItems = workItems;
        this.results = results;
        this.loans = loans;
        this.tasks = tasks;
        this.json = json;
    }

    @Override
    @Transactional
    public LoanInquiryToolResponse execute(LoanInquiryToolRequest request) {
        if (request.workItemId() == null) {
            throw unavailable();
        }
        long workflowId = workItems.findWorkflowIdById(request.workItemId())
                .orElseThrow(JpaLoanInquiryExecutor::unavailable);
        WorkflowEntity workflow = workflows.findForUpdate(workflowId)
                .orElseThrow(JpaLoanInquiryExecutor::unavailable);
        WorkItemEntity item = workItems.findForUpdateByIdAndWorkflowId(request.workItemId(), workflow.id)
                .orElseThrow(JpaLoanInquiryExecutor::unavailable);
        validate(request, workflow, item);

        if (item.status == WorkItemStatus.DONE) {
            return replay(request, item);
        }
        if (item.status != WorkItemStatus.ACTIVE) {
            throw unavailable();
        }

        List<LoanSummary> found = loans.findOwnedLoans(request.customerId(), request.loanId());
        StoredLoanInquiry stored = new StoredLoanInquiry(request.loanId(), found);
        TaskResultEntity result = new TaskResultEntity();
        result.workItemId = item.id;
        result.resultType = RESULT_TYPE;
        result.targetLoanId = found.size() == 1 && request.loanId() != null ? request.loanId() : null;
        result.detail = write(stored);
        results.saveAndFlush(result);

        tasks.complete(request.customerId(), item.id, item.version);
        return completedResponse(found, workflow);
    }

    private LoanInquiryToolResponse replay(LoanInquiryToolRequest request, WorkItemEntity item) {
        TaskResultEntity result = results.findForReadByWorkItemId(item.id)
                .filter(saved -> RESULT_TYPE.equals(saved.resultType))
                .orElseThrow(() -> new DomainException(
                        "TASK_RESULT_NOT_AVAILABLE", "The completed task result is unavailable."));
        StoredLoanInquiry stored = read(result.detail);
        if (!Objects.equals(stored.requestedLoanId(), request.loanId())) {
            throw new DomainException(
                    "DUPLICATE_REQUEST_CONFLICT", "requestId was reused with different loan inquiry conditions.");
        }
        return completedResponse(stored.loans(), workflows.findById(item.workflowId)
                .orElseThrow(JpaLoanInquiryExecutor::unavailable));
    }

    private static LoanInquiryToolResponse completedResponse(
            List<LoanSummary> loans, WorkflowEntity workflow) {
        return new LoanInquiryToolResponse(loans, WorkItemStatus.DONE.name(), workflow.status);
    }

    private static void validate(LoanInquiryToolRequest request, WorkflowEntity workflow, WorkItemEntity item) {
        if (workflow.customerId != request.customerId()
                || item.taskCode != TaskCode.LOAN_INQUIRY
                || !item.requestId.equals(request.requestId())) {
            throw unavailable();
        }
    }

    private String write(StoredLoanInquiry stored) {
        try {
            return json.writeValueAsString(stored);
        } catch (JsonProcessingException exception) {
            throw new DomainException("TASK_RESULT_SERIALIZATION_FAILED", "The loan result could not be stored.");
        }
    }

    private StoredLoanInquiry read(String detail) {
        try {
            return json.readValue(detail, StoredLoanInquiry.class);
        } catch (JsonProcessingException exception) {
            throw new DomainException("TASK_RESULT_INVALID", "The stored loan result is invalid.");
        }
    }

    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "A WorkItem is not available for this task.");
    }

    private record StoredLoanInquiry(Long requestedLoanId, List<LoanSummary> loans) { }
}
