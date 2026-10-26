package com.bankingax.task.c02;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bankingax.core.api.ApiExceptionHandler;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.InMemoryCasePort;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract test for the G3 staff decision -> customer web notification boundary. */
class C02StaffDecisionHttpTest {
    @Test void staff_decision_is_visible_only_to_owner_as_a_web_notification() throws Exception {
        C02IntakeService service=new C02IntakeService(new InMemoryCasePort(),new FixtureVerification(),
            (requestId,caseId,revision,snapshot)->new StaffAgentPort.StaffAgentResponse("SUCCEEDED","READY_FOR_STAFF",List.of(Map.of("category","fixture")),null));
        long caseId=submitted(service);
        MockMvc http=MockMvcBuilders.standaloneSetup(new C02Controller(service,new InternalGatewayAuthenticator("g3-token"))).setControllerAdvice(new ApiExceptionHandler()).build();

        http.perform(get("/api/v1/c02/{caseId}/staff-case",caseId).param("requestId","staff-read").headers(staffHeaders()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.precheck.reviewStatus").value("READY_FOR_STAFF"));
        http.perform(post("/api/v1/c02/{caseId}/staff-decision",caseId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"requestId\":\"staff-decision\",\"outcome\":\"DOCUMENT_INSUFFICIENT\",\"customerMessage\":\"보완 서류를 제출해 주세요.\"}").headers(customerHeaders(1)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        http.perform(post("/api/v1/c02/{caseId}/staff-decision",caseId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"requestId\":\"staff-decision\",\"outcome\":\"DOCUMENT_INSUFFICIENT\",\"customerMessage\":\"보완 서류를 제출해 주세요.\"}").headers(staffHeaders()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.proposedCaseStatus").value("REVIEW_REJECTED_DOCUMENT"));
        http.perform(get("/api/v1/c02/notifications").param("customerId","1").param("requestId","customer-notice").headers(customerHeaders(1)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].outcome").value("DOCUMENT_INSUFFICIENT"));
        http.perform(get("/api/v1/c02/notifications").param("customerId","1").param("requestId","other-customer").headers(customerHeaders(2)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static long submitted(C02IntakeService service) {
        var intake=new C02IntakeService.Intake(new C02IntakeService.DecedentInput("fixture",null,LocalDate.of(2020,1,1),0),
            List.of(new C02IntakeService.HeirInput("h1","heir",LocalDate.of(1980,1,1),"child",true,false,C02IntakeService.VisitStatus.ATTENDING,C02IntakeService.DelegationMethod.NONE,null)));
        Map<String,C02IntakeService.DocumentStatus> docs=Map.of("DECEDENT_DETAILED_FAMILY_RELATION:COMMON",C02IntakeService.DocumentStatus.UPLOADED,"DECEDENT_BASIC_CERTIFICATE:COMMON",C02IntakeService.DocumentStatus.UPLOADED,"HEIR_IDENTITY_CARD:h1",C02IntakeService.DocumentStatus.UPLOADED);
        long caseId=service.prepare(1,801,"prepare-http",intake,docs,"010").caseView().caseId();
        service.recordConsentAndMockSignature(1,caseId,0,true,true);
        var binding=service.verifyOtp(1,service.issueOtp(1,caseId,0).challengeId(),"000000");
        service.submit(new C02IntakeService.C02SubmitCommand(1,caseId,0,"submit-http",binding.bindingId()));
        return caseId;
    }
    private static org.springframework.http.HttpHeaders customerHeaders(long id) { var h=new org.springframework.http.HttpHeaders(); h.add("X-Actor-Id",String.valueOf(id)); h.add("X-Actor-Role","CUSTOMER"); h.add("X-Tool-Gateway-Token","g3-token"); return h; }
    private static org.springframework.http.HttpHeaders staffHeaders() { var h=new org.springframework.http.HttpHeaders(); h.add("X-Actor-Id","900"); h.add("X-Actor-Role","STAFF"); h.add("X-Tool-Gateway-Token","g3-token"); return h; }
    static final class FixtureVerification implements VerificationPort {
        public VerificationIssue issue(VerificationIssueCommand c) { return new VerificationIssue("challenge","010",Instant.now().plusSeconds(60)); }
        public VerificationBinding verify(VerificationVerifyCommand c) { if(!"000000".equals(c.otp())) throw new DomainException("OTP_INVALID","fixture"); return new VerificationBinding("binding",c.customerId(),801,VerificationAction.C02_SUBMIT,0,Instant.now().plusSeconds(60)); }
        public void consume(VerificationConsumeCommand c) { if(!"binding".equals(c.bindingId())) throw new DomainException("VERIFICATION_BINDING_INVALID","fixture"); }
    }
}
