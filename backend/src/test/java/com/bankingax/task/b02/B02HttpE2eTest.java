package com.bankingax.task.b02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.verification.OtpSender;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Real HTTP test server; OTP plaintext stays only in this test bean. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "app.tool-gateway-token=b02-e2e-tool-token",
    "app.reservation.enabled=false"
})
@Import(B02HttpE2eTest.Config.class)
class B02HttpE2eTest {
  @LocalServerPort int port;
  @Autowired TestRestTemplate http;
  @Autowired CapturingOtpSender sender;
  @Autowired FailOnceCloseCommand command;

  @Test void bindsOtp_overHttp_rechecksRules_and_closesOnlyOnce() throws Exception {
    assertEquals(HttpStatus.OK, post("/api/v1/b02/prepare", prepare("r3",true,null), headers(1,"b02-e2e-tool-token")).getStatusCode());
    assertEquals("BASE_RATE", data(post("/api/v1/b02/prepare", prepare("r3-result",true,null), headers(1,"b02-e2e-tool-token"))).get("rate"));
    assertEquals("B2-R3", data(post("/api/v1/b02/prepare", prepare("r3-rule",true,null), headers(1,"b02-e2e-tool-token"))).get("rule"));
    assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/b02/prepare", prepare("bad-token",true,null), headers(1,"wrong")).getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/b02/prepare", prepareFor(2,3001,"foreign",true,null), headers(2,"b02-e2e-tool-token")).getStatusCode());
    Map<String,Object> foreignExecute=execute("foreign-execute",0,true,null,"not-used"); foreignExecute.put("customer",2);
    assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/b02/3001/execute", foreignExecute, headers(2,"b02-e2e-tool-token")).getStatusCode());

    assertCode(post("/api/v1/b02/3001/otp", issue("no-consent",0,false,true), headers(1,"b02-e2e-tool-token")), "CONSENT_OR_SIGNATURE_REQUIRED");
    assertCode(post("/api/v1/b02/3001/otp", issue("stale",1,true,true), headers(1,"b02-e2e-tool-token")), "STATE_CHANGED");

    assertCode(post("/api/v1/b02/3001/execute", execute("manual",0,false,true,bind("manual",0)), headers(1,"b02-e2e-tool-token")), "MANUAL_REVIEW_REQUIRED");
    assertCode(post("/api/v1/b02/3001/execute", execute("document",0,false,null,bind("document",0)), headers(1,"b02-e2e-tool-token")), "REQUIRED_DOCUMENT_MISSING");

    String consumedOnFailure=bind("command-failure",0);
    command.failNext();
    assertCode(post("/api/v1/b02/3001/execute", execute("command-failure",0,true,null,consumedOnFailure), headers(1,"b02-e2e-tool-token")), "MOCK_COMMAND_FAILED");
    assertCode(post("/api/v1/b02/3001/execute", execute("consumed-binding",0,true,null,consumedOnFailure), headers(1,"b02-e2e-tool-token")), "VERIFICATION_BINDING_INVALID");

    Map<String,Object> first=execute("close-a",0,true,null,bind("close-a",0));
    Map<String,Object> second=execute("close-b",0,true,null,bind("close-b",0));
    ExecutorService pool=Executors.newFixedThreadPool(2);
    try {
      List<Future<ResponseEntity<Map>>> outcomes=pool.invokeAll(List.of(
          () -> post("/api/v1/b02/3001/execute", first, headers(1,"b02-e2e-tool-token")),
          () -> post("/api/v1/b02/3001/execute", second, headers(1,"b02-e2e-tool-token"))));
      ResponseEntity<Map> left=outcomes.get(0).get(), right=outcomes.get(1).get();
      assertEquals(1, List.of(left,right).stream().filter(x -> x.getStatusCode()==HttpStatus.OK).count());
      assertEquals(1, List.of(left,right).stream().filter(x -> x.getStatusCode()==HttpStatus.BAD_REQUEST).count());
      ResponseEntity<Map> success=left.getStatusCode()==HttpStatus.OK ? left : right;
      Map<String,Object> winning=left.getStatusCode()==HttpStatus.OK ? first : second;
      assertEquals("CLOSED", data(success).get("status"));
      assertEquals(HttpStatus.OK, post("/api/v1/b02/3001/execute", winning, headers(1,"b02-e2e-tool-token")).getStatusCode());
      Map<String,Object> conflict=new HashMap<>(winning); conflict.put("waive",false);
      assertCode(post("/api/v1/b02/3001/execute", conflict, headers(1,"b02-e2e-tool-token")), "DUPLICATE_REQUEST_CONFLICT");
    } finally { pool.shutdownNow(); }
    assertCode(post("/api/v1/b02/3001/execute", execute("already-closed",0,true,null,"not-used"), headers(1,"b02-e2e-tool-token")), "STATE_CHANGED");
  }

  private String bind(String id,int version) {
    var issued=post("/api/v1/b02/3001/otp", issue(id,version,true,true), headers(1,"b02-e2e-tool-token"));
    assertEquals(HttpStatus.OK,issued.getStatusCode());
    String challenge=(String)data(issued).get("challengeId");
    var verified=post("/api/v1/b02/otp/verify", Map.of("requestId",id+"-verify","customer",1,"challenge",challenge,"otp",sender.codeFor("01012345678")), headers(1,"b02-e2e-tool-token"));
    assertEquals(HttpStatus.OK,verified.getStatusCode());
    return (String)data(verified).get("bindingId");
  }
  private ResponseEntity<Map> post(String path,Map<String,Object> body,HttpHeaders headers) { return http.exchange("http://127.0.0.1:"+port+path,HttpMethod.POST,new HttpEntity<>(body,headers),Map.class); }
  private static HttpHeaders headers(long actor,String token) { HttpHeaders h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.add("X-Actor-Id",String.valueOf(actor)); h.add("X-Actor-Role","CUSTOMER"); h.add("X-Tool-Gateway-Token",token); return h; }
  private static Map<String,Object> prepare(String id,boolean waive,Boolean taxed) { return prepareFor(1,3001,id,waive,taxed); }
  private static Map<String,Object> prepareFor(long customer,long account,String id,boolean waive,Boolean taxed) { Map<String,Object> m=new HashMap<>(); m.put("requestId",id);m.put("customer",customer);m.put("account",account);m.put("waive",waive);m.put("taxed",taxed);return m; }
  private static Map<String,Object> issue(String id,int version,boolean consent,boolean signed) { return Map.of("requestId",id,"customer",1,"version",version,"consent",consent,"signed",signed,"phone","01012345678"); }
  private static Map<String,Object> execute(String id,int version,boolean waive,Boolean taxed,String binding) { Map<String,Object> m=new HashMap<>();m.put("requestId",id);m.put("customer",1);m.put("version",version);m.put("waive",waive);m.put("taxed",taxed);m.put("binding",binding);return m; }
  @SuppressWarnings("unchecked") private static Map<String,Object> data(ResponseEntity<Map> response) { assertEquals(HttpStatus.OK,response.getStatusCode(),String.valueOf(response.getBody())); return (Map<String,Object>)response.getBody().get("data"); }
  @SuppressWarnings("unchecked") private static void assertCode(ResponseEntity<Map> response,String code) { assertEquals(HttpStatus.BAD_REQUEST,response.getStatusCode(),String.valueOf(response.getBody())); assertEquals(code,((Map<String,Object>)response.getBody().get("error")).get("code")); }

  @TestConfiguration static class Config {
    @Bean @Primary CapturingOtpSender capturingOtpSender() { return new CapturingOtpSender(); }
    @Bean @Primary FailOnceCloseCommand failOnceCloseCommand() { return new FailOnceCloseCommand(); }
  }
  static final class CapturingOtpSender implements OtpSender { private final Map<String,String> codes=new ConcurrentHashMap<>(); public void send(String phone,String code) { codes.put(phone,code); } String codeFor(String phone) { return codes.get(phone); } }
  static final class FailOnceCloseCommand implements B02MockCloseCommand { private final AtomicBoolean fail=new AtomicBoolean(); void failNext() { fail.set(true); } public void close(long accountId) { if(fail.compareAndSet(true,false)) throw new DomainException("MOCK_COMMAND_FAILED","Mock close command failed.",true); } }
}
