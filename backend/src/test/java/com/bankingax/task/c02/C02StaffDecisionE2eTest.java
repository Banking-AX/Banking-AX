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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual embedded-Tomcat HTTP E2E for the G3 Spring-only staff outcome boundary. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "app.tool-gateway-token=c02-g3-e2e-token"
})
@Import(C02StaffDecisionE2eTest.Config.class)
class C02StaffDecisionE2eTest {
    /** This context has no DataSource, but b01DocumentService requires a transaction manager. */
    @MockBean PlatformTransactionManager transactions;
    @org.springframework.test.context.DynamicPropertySource static void storage(org.springframework.test.context.DynamicPropertyRegistry properties) throws java.io.IOException {
        String root=java.nio.file.Files.createTempDirectory("c02-e2e").toString();
        properties.add("app.c02.file-storage-root",()->root);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired CapturingOtpSender sender;

    @Test void submitted_case_staff_decision_creates_owner_only_web_notification_over_http() {
        String run="g3-e2e";
        var prepared=post("/api/v1/c02/prepare",prepareBody(run),customerHeaders(1));
        long caseId=((Number)prepared.getBody().get("caseId")).longValue();
        int revision=0;
        for(String slot:List.of("DECEDENT_DETAILED_FAMILY_RELATION:COMMON","DECEDENT_BASIC_CERTIFICATE:COMMON","HEIR_IDENTITY_CARD:h1")) {
            var registered=upload(caseId,revision,run+"-doc-"+slot,slot);
            assertEquals(HttpStatus.OK,registered.getStatusCode());
            revision=((Number)registered.getBody().get("revision")).intValue();
        }
        assertEquals(HttpStatus.OK,post("/api/v1/c02/"+caseId+"/consent",Map.of("customerId",1,"revision",revision,"requestId",run+"-consent","consented",true,"mockSignatureCompleted",true),customerHeaders(1)).getStatusCode());
        var issued=post("/api/v1/c02/"+caseId+"/verification/issue",Map.of("customerId",1,"revision",revision,"requestId",run+"-issue"),customerHeaders(1));
        String challenge=(String)((Map<?,?>)issued.getBody().get("data")).get("challengeId");
        var verified=post("/api/v1/c02/verification/verify",Map.of("customerId",1,"requestId",run+"-verify","challengeId",challenge,"otp",sender.codeFor("01012345678")),customerHeaders(1));
        String binding=(String)((Map<?,?>)verified.getBody().get("data")).get("bindingId");
        var submitted=post("/api/v1/c02/"+caseId+"/submit",Map.of("customerId",1,"revision",revision,"requestId",run+"-submit","bindingId",binding),customerHeaders(1));
        assertEquals(HttpStatus.OK,submitted.getStatusCode());
        int submittedRevision=((Number)submitted.getBody().get("revision")).intValue();
        assertEquals(HttpStatus.OK,post("/api/v1/c02/"+caseId+"/staff-precheck",Map.of("revision",submittedRevision,"requestId",run+"-precheck"),staffHeaders()).getStatusCode());
        var decision=post("/api/v1/c02/"+caseId+"/staff-decision",Map.of("revision",submittedRevision,"requestId",run+"-decision","outcome","PROCEED","customerMessage","직원 검토 완료"),staffHeaders());
        assertEquals(HttpStatus.OK,decision.getStatusCode());
        assertEquals("REVIEW_PASSED",((Map<?,?>)decision.getBody().get("data")).get("proposedCaseStatus"));
        var notifications=http.exchange(url("/api/v1/c02/notifications?customerId=1&requestId="+run+"-notice"),HttpMethod.GET,new HttpEntity<>(customerHeaders(1)),Map.class);
        assertEquals(HttpStatus.OK,notifications.getStatusCode());
        assertEquals("PROCEED",((Map<?,?>)((List<?>)notifications.getBody().get("data")).get(0)).get("outcome"));
    }

    /** A real multipart upload: metadata alone is not submit evidence. */
    private ResponseEntity<Map> upload(long caseId,int revision,String requestId,String slot) {
        var form=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        form.add("customerId","1"); form.add("revision",String.valueOf(revision)); form.add("requestId",requestId); form.add("documentSlotKey",slot);
        var pdf=new HttpHeaders(); pdf.setContentType(MediaType.APPLICATION_PDF);
        var bytes=new org.springframework.core.io.ByteArrayResource("%PDF-1.4\nsynthetic fixture".getBytes()) { @Override public String getFilename() { return "fixture.pdf"; } };
        form.add("file",new HttpEntity<>(bytes,pdf));
        var headers=customerHeaders(1); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(url("/api/v1/c02/"+caseId+"/documents/upload"),HttpMethod.POST,new HttpEntity<>(form,headers),Map.class);
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
