package com.bankingax.core.tool;

import com.bankingax.core.api.DomainException;
import com.bankingax.task.a02.LoanQueryPort;
import org.springframework.stereotype.Service;

@Service
public class ToolGatewayService {
    private final LoanQueryPort loanQueryPort;
    public ToolGatewayService(LoanQueryPort loanQueryPort) { this.loanQueryPort = loanQueryPort; }
    public LoanInquiryToolResponse loanInquiry(ToolActor actor, LoanInquiryToolRequest request) {
        if (actor.role() != ActorRole.CUSTOMER || actor.actorId() != request.customerId())
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        if (request.requestId() == null || request.requestId().isBlank())
            throw new DomainException("INVALID_REQUEST", "requestId is required.");
        return new LoanInquiryToolResponse(loanQueryPort.findOwnedLoans(request.customerId(), request.loanId()));
    }
}
