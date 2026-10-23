package com.bankingax.core.tool;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.workflow.TaskCode;
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
    public ToolGatewayController(ToolGatewayService gateway, InternalGatewayAuthenticator authenticator) { this.gateway = gateway; this.authenticator = authenticator; }
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
}
