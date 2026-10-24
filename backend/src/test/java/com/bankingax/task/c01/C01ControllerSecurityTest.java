package com.bankingax.task.c01;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.ApiExceptionHandler;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.VerificationAction;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = C01Controller.class, properties = "app.tool-gateway-token=test-gateway-token")
@Import({ApiExceptionHandler.class, C01ControllerSecurityTest.Config.class})
class C01ControllerSecurityTest {
  private static final String TOKEN = "test-gateway-token";
  @Autowired MockMvc mvc;
  @MockBean C01IntakeService service;

  @Test void everySensitiveEndpointRejectsMissingOrWrongGatewayToken() throws Exception {
    for (RequestSpec spec : sensitiveRequests()) {
      perform(spec, null, 1, "CUSTOMER").andExpect(status().isForbidden());
      perform(spec, "wrong-token", 1, "CUSTOMER").andExpect(status().isForbidden());
    }
  }

  @Test void everySensitiveEndpointRejectsActorCustomerMismatchAndWrongRole() throws Exception {
    for (RequestSpec spec : sensitiveRequests()) {
      perform(spec, TOKEN, 2, "CUSTOMER").andExpect(status().isForbidden());
      perform(spec, TOKEN, 1, "STAFF").andExpect(status().isForbidden());
    }
  }

  @Test void authenticatedCustomerCanUseEverySensitiveEndpointAndKeepsNullReviewStatus() throws Exception {
    var draft = new C01IntakeService.C01Draft(2001, 1001, 0, "DRAFT", null, false, null);
    var submission = new C01IntakeService.C01Submission(1, 2001, 1001, 0, "SUBMITTED", "PENDING", false, List.of());
    when(service.start(any())).thenReturn(draft);
    when(service.saveIntake(any(Long.class), any(Long.class), any(Integer.class), any(), any(), any(Long.class), any(Boolean.class))).thenReturn(draft);
    when(service.recordConsentAndMockSignature(any(Long.class), any(Long.class), any(Integer.class), any(Boolean.class), any(Boolean.class))).thenReturn(draft);
    when(service.issueOtp(any(Long.class), any(Long.class), any(Integer.class))).thenReturn(new VerificationIssue("challenge", "010-****-5678", Instant.parse("2026-01-01T00:00:00Z")));
    when(service.verifyOtp(any(Long.class), any(), any())).thenReturn(new VerificationBinding("binding", 1, 1001, VerificationAction.C01_SUBMIT, 0, Instant.parse("2026-01-01T00:00:00Z")));
    when(service.submit(any())).thenReturn(submission);

    for (RequestSpec spec : sensitiveRequests()) perform(spec, TOKEN, 1, "CUSTOMER").andExpect(status().isOk());
    perform(sensitiveRequests()[1], TOKEN, 1, "CUSTOMER").andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewStatus").value(org.hamcrest.Matchers.nullValue()));
    verify(service, times(2)).saveIntake(1, 2001, 0, "reason", "situation", 100, true);
    verify(service).issueOtp(1, 2001, 0);
    verify(service).verifyOtp(1, "challenge", "000000");
    verify(service).submit(any());
  }

  private org.springframework.test.web.servlet.ResultActions perform(RequestSpec spec, String token, long actorId, String role) throws Exception {
    var builder = spec.put ? put(spec.path) : post(spec.path);
    builder.contentType(MediaType.APPLICATION_JSON).content(spec.body).header("X-Actor-Id", actorId).header("X-Actor-Role", role);
    if (token != null) builder.header("X-Tool-Gateway-Token", token);
    return mvc.perform(builder);
  }

  private static RequestSpec[] sensitiveRequests() {
    return new RequestSpec[] {
        new RequestSpec(false, "/api/v1/c01/start", "{\"customerId\":1,\"workflowId\":10,\"expectedWorkflowVersion\":0,\"requestId\":\"start\",\"loanId\":101,\"registeredPhone\":\"01012345678\"}"),
        new RequestSpec(true, "/api/v1/c01/2001/intake", "{\"customerId\":1,\"revision\":0,\"requestId\":\"intake\",\"reason\":\"reason\",\"currentSituation\":\"situation\",\"monthlyRepaymentCapacity\":100,\"requiredDocumentsSatisfied\":true}"),
        new RequestSpec(false, "/api/v1/c01/2001/consent", "{\"customerId\":1,\"revision\":0,\"requestId\":\"consent\",\"consented\":true,\"mockSignatureCompleted\":true}"),
        new RequestSpec(false, "/api/v1/c01/2001/verification/issue", "{\"customerId\":1,\"revision\":0,\"requestId\":\"issue\"}"),
        new RequestSpec(false, "/api/v1/c01/verification/verify", "{\"customerId\":1,\"requestId\":\"verify\",\"challengeId\":\"challenge\",\"otp\":\"000000\"}"),
        new RequestSpec(false, "/api/v1/c01/2001/submit", "{\"customerId\":1,\"revision\":0,\"requestId\":\"submit\",\"bindingId\":\"binding\"}")
    };
  }
  private record RequestSpec(boolean put, String path, String body) { }
  @TestConfiguration static class Config {
    @Bean InternalGatewayAuthenticator internalGatewayAuthenticator(org.springframework.core.env.Environment environment) {
      return new InternalGatewayAuthenticator(environment.getProperty("app.tool-gateway-token", ""));
    }
  }
}
