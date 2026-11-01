package com.bankingax.core.tool;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.task.a01.TransactionSearchRequest;
import com.bankingax.task.a01.TransactionSearchResponse;
import com.bankingax.task.a01.TransactionSearchService;
import com.bankingax.task.a01.A01WorkflowService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolGatewayController {
    private final ToolGatewayService gateway;
    private final InternalGatewayAuthenticator authenticator;
    private final TransactionSearchService transactionSearch;
    private final A01WorkflowService a01Workflow;
    public ToolGatewayController(ToolGatewayService gateway, InternalGatewayAuthenticator authenticator,
                                 TransactionSearchService transactionSearch, ObjectProvider<A01WorkflowService> a01Workflow) {
        this.gateway = gateway; this.authenticator = authenticator; this.transactionSearch = transactionSearch; this.a01Workflow=a01Workflow.getIfAvailable();
    }
    @PostMapping("/loan-inquiry")
    public ApiEnvelope<LoanInquiryToolResponse> loanInquiry(
            @RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader("X-Tool-Gateway-Token") String gatewayToken,
            @RequestBody LoanInquiryToolRequest request) {
        authenticator.verify(gatewayToken);
        LoanInquiryToolResponse response = gateway.loanInquiry(new ToolActor(actorId, role), request);
        return ApiEnvelope.success(request.requestId(), TaskCode.LOAN_INQUIRY.name(), request.workItemId(), null, null, response);
    }
    @PostMapping("/transaction-search")
    public ApiEnvelope<TransactionSearchResponse> transactionSearch(
            @RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader("X-Tool-Gateway-Token") String gatewayToken,
            @RequestBody TransactionSearchRequest request) {
        authenticator.verify(gatewayToken);
        TransactionSearchResponse response = a01Workflow == null ? transactionSearch.search(new ToolActor(actorId, role), request) : a01Workflow.execute(new ToolActor(actorId, role), request);
        return ApiEnvelope.success(request.requestId(), TaskCode.TRANSACTION_SEARCH.name(), request.workItemId(), null, null, response);
    }
}
