package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Internal Tool boundary for C-02 preparation; it never submits or approves an inheritance claim. */
@RestController
@RequestMapping("/api/v1/c02")
public class C02Controller {
    private final C02IntakeService service; private final InternalGatewayAuthenticator authenticator;
    public C02Controller(C02IntakeService service, InternalGatewayAuthenticator authenticator) { this.service=service; this.authenticator=authenticator; }
    @PostMapping("/prepare") public ApiEnvelope<C02IntakeService.Prepared> prepare(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role, @RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String token,
            @RequestBody PrepareBody body) {
        authenticateCustomer(actorId, role, token, body.customerId());
        var intake = new C02IntakeService.Intake(new C02IntakeService.DecedentInput(body.decedent().name(), body.decedent().birthDate(), body.decedent().deathDate(), body.decedent().depositTotal()),
            body.heirs().stream().map(h -> new C02IntakeService.HeirInput(h.personRef(),h.name(),h.birthDate(),h.relationship(),h.applicant(),h.higherOrderOrSubrogation(),h.visitStatus(),h.delegationMethod(),h.delegationReceiptNo())).toList());
        var data=service.prepare(body.customerId(),body.workItemId(),body.requestId(),intake,body.documentStatuses()==null ? Map.of() : body.documentStatuses(),body.registeredPhone());
        Long caseId=data.caseView()==null ? null : data.caseView().caseId(); Integer revision=data.caseView()==null ? null : data.caseView().revision();
        return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",body.workItemId(),caseId,revision,data);
    }
    @PostMapping("/{caseId}/consent") public ApiEnvelope<C02IntakeService.C02Draft> consent(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody ConsentBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.recordConsentAndMockSignature(body.customerId(),caseId,body.revision(),body.consented(),body.mockSignatureCompleted()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @PostMapping("/{caseId}/verification/issue") public ApiEnvelope<VerificationIssue> issue(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody RevisionBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.issueOtp(body.customerId(),caseId,body.revision()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",null,caseId,body.revision(),data);
    }
    @PostMapping("/verification/verify") public ApiEnvelope<VerificationBinding> verify(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody VerifyBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.verifyOtp(body.customerId(),body.challengeId(),body.otp()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",null,null,null,data);
    }
    @PostMapping("/{caseId}/submit") public ApiEnvelope<C02IntakeService.SubmissionResult> submit(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody SubmitBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.submit(new C02IntakeService.C02SubmitCommand(body.customerId(),caseId,body.revision(),body.requestId(),body.bindingId())); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    private void authenticateCustomer(long actorId, ActorRole role, String token, long customerId) { authenticator.verify(token); if(role!=ActorRole.CUSTOMER || actorId!=customerId) throw new DomainException("FORBIDDEN","Customer ownership cannot be verified."); }
    public record PrepareBody(long customerId,long workItemId,String requestId,String registeredPhone,DecedentBody decedent,List<HeirBody> heirs,Map<String,C02IntakeService.DocumentStatus> documentStatuses) {}
    public record DecedentBody(String name,LocalDate birthDate,LocalDate deathDate,long depositTotal) {}
    public record HeirBody(String personRef,String name,LocalDate birthDate,String relationship,boolean applicant,boolean higherOrderOrSubrogation,C02IntakeService.VisitStatus visitStatus,C02IntakeService.DelegationMethod delegationMethod,String delegationReceiptNo) {}
    public record ConsentBody(long customerId,int revision,String requestId,boolean consented,boolean mockSignatureCompleted) {}
    public record RevisionBody(long customerId,int revision,String requestId) {}
    public record VerifyBody(long customerId,String requestId,String challengeId,String otp) {}
    public record SubmitBody(long customerId,int revision,String requestId,String bindingId) {}
}
