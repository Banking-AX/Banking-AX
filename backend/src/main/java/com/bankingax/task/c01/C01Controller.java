package com.bankingax.task.c01;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import org.springframework.web.bind.annotation.*;

/** Customer-facing C-01 boundary. Authentication is supplied by the gateway in this Fake slice. */
@RestController
@RequestMapping("/api/v1/c01")
public class C01Controller {
    private final C01IntakeService service;
    private final InternalGatewayAuthenticator authenticator;
    public C01Controller(C01IntakeService service, InternalGatewayAuthenticator authenticator) { this.service = service; this.authenticator = authenticator; }
    @PostMapping("/start") public ApiEnvelope<C01IntakeService.C01Draft> start(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role, @RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String gatewayToken, @RequestBody StartBody b) {
        authenticateCustomer(actorId, role, gatewayToken, b.customerId);
        var data=service.start(new C01IntakeService.C01StartCommand(b.customerId,b.workflowId,b.expectedWorkflowVersion,b.requestId,b.loanId,b.registeredPhone));
        return ApiEnvelope.success(b.requestId,"DEBT_ADJUSTMENT",data.workItemId(),data.caseId(),data.revision(),data);
    }
    @PutMapping("/{caseId}/intake") public ApiEnvelope<C01IntakeService.C01Draft> intake(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String gatewayToken,@RequestBody IntakeBody b) {
        authenticateCustomer(actorId, role, gatewayToken, b.customerId);
        var data=service.saveIntake(b.customerId,caseId,b.revision,b.reason,b.currentSituation,b.monthlyRepaymentCapacity,b.requiredDocumentsSatisfied);
        return ApiEnvelope.success(b.requestId,"DEBT_ADJUSTMENT",data.workItemId(),caseId,data.revision(),data);
    }
    @PostMapping("/{caseId}/consent") public ApiEnvelope<C01IntakeService.C01Draft> consent(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String gatewayToken,@RequestBody ConsentBody b) {
        authenticateCustomer(actorId, role, gatewayToken, b.customerId);
        var data=service.recordConsentAndMockSignature(b.customerId,caseId,b.revision,b.consented,b.mockSignatureCompleted);
        return ApiEnvelope.success(b.requestId,"DEBT_ADJUSTMENT",data.workItemId(),caseId,data.revision(),data);
    }
    @PostMapping("/{caseId}/verification/issue") public ApiEnvelope<VerificationIssue> issue(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String gatewayToken,@RequestBody RevisionBody b) {
        authenticateCustomer(actorId, role, gatewayToken, b.customerId);
        var data=service.issueOtp(b.customerId,caseId,b.revision); return ApiEnvelope.success(b.requestId,"DEBT_ADJUSTMENT",null,caseId,b.revision,data);
    }
    @PostMapping("/verification/verify") public ApiEnvelope<VerificationBinding> verify(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String gatewayToken,@RequestBody VerifyBody b) {
        authenticateCustomer(actorId, role, gatewayToken, b.customerId);
        var data=service.verifyOtp(b.customerId,b.challengeId,b.otp); return ApiEnvelope.success(b.requestId,"DEBT_ADJUSTMENT",null,null,null,data);
    }
    @PostMapping("/{caseId}/submit") public ApiEnvelope<C01IntakeService.C01Submission> submit(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String gatewayToken,@RequestBody SubmitBody b) {
        authenticateCustomer(actorId, role, gatewayToken, b.customerId);
        var data=service.submit(new C01IntakeService.C01SubmitCommand(b.customerId,caseId,b.revision,b.requestId,b.bindingId));
        return ApiEnvelope.success(b.requestId,"DEBT_ADJUSTMENT",data.workItemId(),caseId,data.revision(),data);
    }
    private void authenticateCustomer(long actorId, ActorRole role, String gatewayToken, long customerId) {
        authenticator.verify(gatewayToken);
        if (role != ActorRole.CUSTOMER || actorId != customerId)
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
    }
    public record StartBody(long customerId,long workflowId,int expectedWorkflowVersion,String requestId,long loanId,String registeredPhone){}
    public record IntakeBody(long customerId,int revision,String requestId,String reason,String currentSituation,long monthlyRepaymentCapacity,boolean requiredDocumentsSatisfied){}
    public record ConsentBody(long customerId,int revision,String requestId,boolean consented,boolean mockSignatureCompleted){}
    public record RevisionBody(long customerId,int revision,String requestId){}
    public record VerifyBody(long customerId,String requestId,String challengeId,String otp){}
    public record SubmitBody(long customerId,int revision,String requestId,String bindingId){}
}
