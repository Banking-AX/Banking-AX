package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
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
        var data=service.prepare(body.customerId(),body.workItemId(),body.requestId(),intake,body.documentStatuses()==null ? Map.of() : body.documentStatuses());
        Long caseId=data.caseView()==null ? null : data.caseView().caseId(); Integer revision=data.caseView()==null ? null : data.caseView().revision();
        return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",body.workItemId(),caseId,revision,data);
    }
    private void authenticateCustomer(long actorId, ActorRole role, String token, long customerId) { authenticator.verify(token); if(role!=ActorRole.CUSTOMER || actorId!=customerId) throw new DomainException("FORBIDDEN","Customer ownership cannot be verified."); }
    public record PrepareBody(long customerId,long workItemId,String requestId,DecedentBody decedent,List<HeirBody> heirs,Map<String,C02IntakeService.DocumentStatus> documentStatuses) {}
    public record DecedentBody(String name,LocalDate birthDate,LocalDate deathDate,long depositTotal) {}
    public record HeirBody(String personRef,String name,LocalDate birthDate,String relationship,boolean applicant,boolean higherOrderOrSubrogation,C02IntakeService.VisitStatus visitStatus,C02IntakeService.DelegationMethod delegationMethod,String delegationReceiptNo) {}
}
