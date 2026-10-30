package com.bankingax.core.tool;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.ActiveWorkItemValidator;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.task.a02.LoanQueryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ToolGatewayService {
    private final LoanInquiryExecutor loanInquiries;

    @Autowired
    public ToolGatewayService(LoanInquiryExecutor loanInquiries) {
        this.loanInquiries = loanInquiries;
    }

    /** Keeps the Phase 0 fixture construction contract while runtime wiring uses LoanInquiryExecutor. */
    public ToolGatewayService(LoanQueryPort loans, ActiveWorkItemValidator workItems) {
        this(request -> {
            workItems.validate(request.workItemId(), request.customerId(), TaskCode.LOAN_INQUIRY);
            return new LoanInquiryToolResponse(loans.findOwnedLoans(request.customerId(), request.loanId()));
        });
    }

    public LoanInquiryToolResponse loanInquiry(ToolActor actor, LoanInquiryToolRequest request) {
        if (actor.role() != ActorRole.CUSTOMER || actor.actorId() != request.customerId())
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        if (request.requestId() == null || request.requestId().isBlank())
            throw new DomainException("INVALID_REQUEST", "requestId is required.");
        return loanInquiries.execute(request);
    }
}
