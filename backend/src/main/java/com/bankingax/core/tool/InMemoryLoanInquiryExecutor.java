package com.bankingax.core.tool;

import com.bankingax.core.workflow.ActiveWorkItemValidator;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.task.a02.LoanQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.case-port", havingValue = "inmemory", matchIfMissing = true)
class InMemoryLoanInquiryExecutor implements LoanInquiryExecutor {
    private final LoanQueryPort loans;
    private final ActiveWorkItemValidator workItems;

    InMemoryLoanInquiryExecutor(LoanQueryPort loans, ActiveWorkItemValidator workItems) {
        this.loans = loans;
        this.workItems = workItems;
    }

    @Override
    public LoanInquiryToolResponse execute(LoanInquiryToolRequest request) {
        workItems.validate(request.workItemId(), request.customerId(), TaskCode.LOAN_INQUIRY);
        return new LoanInquiryToolResponse(loans.findOwnedLoans(request.customerId(), request.loanId()));
    }
}
