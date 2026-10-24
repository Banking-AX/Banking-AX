package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.verification.OtpSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;

/** Runs only when a separately started FastAPI staff-agent is explicitly supplied. */
@EnabledIfSystemProperty(named="c02.e2e.enabled", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "app.tool-gateway-token=c02-e2e-tool-token",
    "app.staff-agent-token=c02-e2e-staff-token",
    "app.staff-agent-base-url=${c02.staff.url:http://127.0.0.1:18001}"
})
@Import(C02HttpE2eTest.Config.class)
class C02HttpE2eTest {
  @LocalServerPort int port;
  @Autowired TestRestTemplate http;
  @Autowired CapturingOtpSender sender;
  @Autowired ObjectMapper mapper;

  @Test void submitBindsOtpAndCallsExternalStaffAgentOverHttp() {
    String run = "e2e-" + UUID.randomUUID();
    var prepare=post("/api/v1/c02/prepare",prepareBody(run),headers(1));
    assertEquals(HttpStatus.OK,prepare.getStatusCode());
    long caseId=((Number)prepare.getBody().get("caseId")).longValue();
    assertEquals(Boolean.TRUE,((Map<?,?>)((Map<?,?>)prepare.getBody().get("data")).get("caseView")).get("visitRequired"));
    assertEquals(HttpStatus.OK,post("/api/v1/c02/"+caseId+"/consent",Map.of("customerId",1,"revision",0,"requestId",run+"-consent","consented",true,"mockSignatureCompleted",true),headers(1)).getStatusCode());
    var issue=post("/api/v1/c02/"+caseId+"/verification/issue",Map.of("customerId",1,"revision",0,"requestId",run+"-issue"),headers(1));
    assertEquals(HttpStatus.OK,issue.getStatusCode());
    String challengeId=(String)((Map<?,?>)issue.getBody().get("data")).get("challengeId");
    var verify=post("/api/v1/c02/verification/verify",Map.of("customerId",1,"requestId",run+"-verify","challengeId",challengeId,"otp",sender.codeFor("01012345678")),headers(1));
    assertEquals(HttpStatus.OK,verify.getStatusCode());
    String binding=(String)((Map<?,?>)verify.getBody().get("data")).get("bindingId");
    Map<String,Object> submit=Map.of("customerId",1,"revision",0,"requestId",run+"-submit","bindingId",binding);
    var submitted=post("/api/v1/c02/"+caseId+"/submit",submit,headers(1));
    assertEquals(HttpStatus.OK,submitted.getStatusCode());
    Map<?,?> data=(Map<?,?>)submitted.getBody().get("data");
    assertEquals("SUBMITTED",data.get("caseStatus")); assertEquals("SUCCEEDED",data.get("agentRunStatus"),String.valueOf(data)); assertEquals("READY_FOR_STAFF",data.get("reviewStatus"));
    assertEquals(HttpStatus.OK,post("/api/v1/c02/"+caseId+"/submit",submit,headers(1)).getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST,post("/api/v1/c02/"+caseId+"/submit",Map.of("customerId",1,"revision",0,"requestId",run+"-submit","bindingId","other"),headers(1)).getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST,post("/api/v1/c02/"+caseId+"/submit",Map.of("customerId",1,"revision",0,"requestId",run+"-reused-binding","bindingId",binding),headers(1)).getStatusCode());
    var staleIssue=post("/api/v1/c02/"+caseId+"/verification/issue",Map.of("customerId",1,"revision",0,"requestId",run+"-stale-issue"),headers(1));
    String staleChallenge=(String)((Map<?,?>)staleIssue.getBody().get("data")).get("challengeId");
    var staleVerify=post("/api/v1/c02/verification/verify",Map.of("customerId",1,"requestId",run+"-stale-verify","challengeId",staleChallenge,"otp",sender.codeFor("01012345678")),headers(1));
    String staleBinding=(String)((Map<?,?>)staleVerify.getBody().get("data")).get("bindingId");
    assertEquals(HttpStatus.BAD_REQUEST,post("/api/v1/c02/"+caseId+"/submit",Map.of("customerId",1,"revision",1,"requestId",run+"-stale","bindingId",staleBinding),headers(1)).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN,post("/api/v1/c02/"+caseId+"/consent",Map.of("customerId",1,"revision",0,"requestId",run+"-owner","consented",true,"mockSignatureCompleted",true),headers(2)).getStatusCode());
  }

  @Test void staffClientRejectsWrongTokenAndUnavailableServer() {
    assertEquals("SUCCEEDED",new StaffAgentHttpClient(System.getProperty("c02.staff.url","http://127.0.0.1:18001"),"c02-e2e-staff-token",mapper).check("ok",1,0,Map.of("decedent",Map.of("deathDate","2020-01-01"),"heirs",java.util.List.of())).status());
    assertThrows(DomainException.class,()->new StaffAgentHttpClient(System.getProperty("c02.staff.url","http://127.0.0.1:18001"),"wrong",mapper).check("bad-token",1,0,Map.of()));
    assertThrows(DomainException.class,()->new StaffAgentHttpClient("http://127.0.0.1:1","c02-e2e-staff-token",mapper).check("down",1,0,Map.of()));
  }

  @Test void staffClientTimesOutAgainstAnUnresponsiveInternalEndpoint() throws Exception {
    HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    slow.createContext("/api/v1/staff-agent/check", exchange -> {
      try { Thread.sleep(6000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      exchange.sendResponseHeaders(200, 0); exchange.close();
    });
    slow.start();
    try {
      assertThrows(DomainException.class, () -> new StaffAgentHttpClient("http://127.0.0.1:" + slow.getAddress().getPort(), "c02-e2e-staff-token", mapper).check("timeout", 1, 0, Map.of()));
    } finally { slow.stop(0); }
  }

  private ResponseEntity<Map> post(String path,Map<String,Object> body,HttpHeaders headers) { return http.exchange("http://127.0.0.1:"+port+path,HttpMethod.POST,new HttpEntity<>(body,headers),Map.class); }
  private static HttpHeaders headers(long actor) { HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.add("X-Actor-Id",String.valueOf(actor)); headers.add("X-Actor-Role","CUSTOMER"); headers.add("X-Tool-Gateway-Token","c02-e2e-tool-token"); return headers; }
  private static Map<String,Object> prepareBody(String run) { return Map.of("customerId",1,"workItemId",7001,"requestId",run+"-prepare","registeredPhone","01012345678","decedent",Map.of("name","fixture","deathDate","2020-01-01","depositTotal",0),"heirs",java.util.List.of(Map.of("personRef","h1","name","heir","birthDate","1980-01-01","relationship","child","applicant",true,"higherOrderOrSubrogation",false,"visitStatus","ATTENDING","delegationMethod","NONE")),"documentStatuses",Map.of("DECEDENT_DETAILED_FAMILY_RELATION:COMMON","UPLOADED","DECEDENT_BASIC_CERTIFICATE:COMMON","UPLOADED","HEIR_IDENTITY_CARD:h1","UPLOADED")); }
  @TestConfiguration static class Config { @Bean @Primary CapturingOtpSender capturingOtpSender(){ return new CapturingOtpSender(); } }
  static final class CapturingOtpSender implements OtpSender { private final Map<String,String> codes=new ConcurrentHashMap<>(); public void send(String phone,String code){ codes.put(phone,code); } String codeFor(String phone){ return codes.get(phone); } }
}
