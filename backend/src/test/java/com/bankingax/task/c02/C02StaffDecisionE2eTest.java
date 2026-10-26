package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bankingax.core.verification.OtpSender;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;

/** Actual embedded-Tomcat HTTP E2E for the G3 Spring-only staff outcome boundary. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "app.tool-gateway-token=c02-g3-e2e-token"
})
@Import(C02StaffDecisionE2eTest.Config.class)
class C02StaffDecisionE2eTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired CapturingOtpSender sender;

    @Test void submitted_case_staff_decision_creates_owner_only_web_notification_over_http() {
        String run="g3-e2e";
        var prepared=post("/api/v1/c02/prepare",prepareBody(run),customerHeaders(1));
        long caseId=((Number)prepared.getBody().get("caseId")).longValue();
        assertEquals(HttpStatus.OK,post("/api/v1/c02/"+caseId+"/consent",Map.of("customerId",1,"revision",0,"requestId",run+"-consent","consented",true,"mockSignatureCompleted",true),customerHeaders(1)).getStatusCode());
        var issued=post("/api/v1/c02/"+caseId+"/verification/issue",Map.of("customerId",1,"revision",0,"requestId",run+"-issue"),customerHeaders(1));
        String challenge=(String)((Map<?,?>)issued.getBody().get("data")).get("challengeId");
        var verified=post("/api/v1/c02/verification/verify",Map.of("customerId",1,"requestId",run+"-verify","challengeId",challenge,"otp",sender.codeFor("01012345678")),customerHeaders(1));
        String binding=(String)((Map<?,?>)verified.getBody().get("data")).get("bindingId");
        assertEquals(HttpStatus.OK,post("/api/v1/c02/"+caseId+"/submit",Map.of("customerId",1,"revision",0,"requestId",run+"-submit","bindingId",binding),customerHeaders(1)).getStatusCode());
        var decision=post("/api/v1/c02/"+caseId+"/staff-decision",Map.of("revision",0,"requestId",run+"-decision","outcome","PROCEED","customerMessage","직원 검토 완료"),staffHeaders());
        assertEquals(HttpStatus.OK,decision.getStatusCode());
        assertEquals("REVIEW_PASSED",((Map<?,?>)decision.getBody().get("data")).get("proposedCaseStatus"));
        var notifications=http.exchange(url("/api/v1/c02/notifications?customerId=1&requestId="+run+"-notice"),HttpMethod.GET,new HttpEntity<>(customerHeaders(1)),Map.class);
        assertEquals(HttpStatus.OK,notifications.getStatusCode());
        assertEquals("PROCEED",((Map<?,?>)((List<?>)notifications.getBody().get("data")).get(0)).get("outcome"));
    }

    private ResponseEntity<Map> post(String path,Map<String,Object> body,HttpHeaders headers) { return http.exchange(url(path),HttpMethod.POST,new HttpEntity<>(body,headers),Map.class); }
    private String url(String path) { return "http://127.0.0.1:"+port+path; }
    private static HttpHeaders customerHeaders(long id) { var h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.add("X-Actor-Id",String.valueOf(id)); h.add("X-Actor-Role","CUSTOMER"); h.add("X-Tool-Gateway-Token","c02-g3-e2e-token"); return h; }
    private static HttpHeaders staffHeaders() { var h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.add("X-Actor-Id","900"); h.add("X-Actor-Role","STAFF"); h.add("X-Tool-Gateway-Token","c02-g3-e2e-token"); return h; }
    private static Map<String,Object> prepareBody(String run) { return Map.of("customerId",1,"workItemId",812,"requestId",run+"-prepare","registeredPhone","01012345678","decedent",Map.of("name","fixture","deathDate","2020-01-01","depositTotal",0),"heirs",List.of(Map.of("personRef","h1","name","heir","birthDate","1980-01-01","relationship","child","applicant",true,"higherOrderOrSubrogation",false,"visitStatus","ATTENDING","delegationMethod","NONE")),"documentStatuses",Map.of("DECEDENT_DETAILED_FAMILY_RELATION:COMMON","UPLOADED","DECEDENT_BASIC_CERTIFICATE:COMMON","UPLOADED","HEIR_IDENTITY_CARD:h1","UPLOADED")); }

    @TestConfiguration static class Config {
        @Bean @Primary CapturingOtpSender capturingOtpSender(){ return new CapturingOtpSender(); }
        @Bean @Primary StaffAgentPort fixtureStaffAgent(){ return (requestId,caseId,revision,snapshot)->new StaffAgentPort.StaffAgentResponse("SUCCEEDED","READY_FOR_STAFF",List.of(Map.of("source","fixture")),null); }
    }
    static final class CapturingOtpSender implements OtpSender { private final Map<String,String> codes=new ConcurrentHashMap<>(); public void send(String phone,String code){codes.put(phone,code);} String codeFor(String phone){return codes.get(phone);} }
}
