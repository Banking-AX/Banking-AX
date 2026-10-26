package com.bankingax.task.c02;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bankingax.core.api.ApiExceptionHandler;
import com.bankingax.core.caseport.InMemoryCasePort;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class C02ControllerSmokeTest {
    @Test void customer_role_cannot_access_staff_precheck_or_staff_review_even_with_valid_gateway_token() throws Exception {
        C02IntakeService service=new C02IntakeService(new InMemoryCasePort(),new VerificationPort() {
            public VerificationIssue issue(VerificationIssueCommand command){ return new VerificationIssue("x","010",Instant.now()); }
            public VerificationBinding verify(VerificationVerifyCommand command){ return null; }
            public void consume(VerificationConsumeCommand command) {}
        },(requestId,caseId,revision,snapshot)->new StaffAgentPort.StaffAgentResponse("SUCCEEDED","READY_FOR_STAFF",List.of(),null));
        MockMvc http=MockMvcBuilders.standaloneSetup(new C02Controller(service,new InternalGatewayAuthenticator("smoke-token"))).setControllerAdvice(new ApiExceptionHandler()).build();
        http.perform(get("/api/v1/c02/{caseId}/staff-review",2001).param("requestId","customer-staff-review").header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","smoke-token"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        http.perform(post("/api/v1/c02/{caseId}/staff-precheck",2001).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"requestId\":\"customer-precheck\"}").header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","smoke-token"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
    @Test void customer_can_register_and_read_fake_document_metadata_over_http() throws Exception {
        C02IntakeService service=new C02IntakeService(new InMemoryCasePort(),new VerificationPort() {
            public VerificationIssue issue(VerificationIssueCommand command){ return new VerificationIssue("x","010",Instant.now()); }
            public VerificationBinding verify(VerificationVerifyCommand command){ return null; }
            public void consume(VerificationConsumeCommand command) {}
        },(requestId,caseId,revision,snapshot)->new StaffAgentPort.StaffAgentResponse("SUCCEEDED","READY_FOR_STAFF",List.of(),null));
        MockMvc http=MockMvcBuilders.standaloneSetup(new C02Controller(service,new InternalGatewayAuthenticator("smoke-token"))).setControllerAdvice(new ApiExceptionHandler()).build();
        String headers=""; // keeps the request setup below readable.
        String prepare="{\"customerId\":1,\"workItemId\":701,\"requestId\":\"prepare\",\"registeredPhone\":\"010\",\"decedent\":{\"name\":\"fixture\",\"deathDate\":\"2020-01-01\",\"depositTotal\":0},\"heirs\":[{\"personRef\":\"h1\",\"name\":\"heir\",\"birthDate\":\"1980-01-01\",\"relationship\":\"child\",\"applicant\":true,\"higherOrderOrSubrogation\":false,\"visitStatus\":\"ATTENDING\",\"delegationMethod\":\"NONE\"}],\"documentStatuses\":{}}";
        String response=http.perform(post("/api/v1/c02/prepare").contentType(MediaType.APPLICATION_JSON).content(prepare).header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","smoke-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.caseId").isNumber()).andReturn().getResponse().getContentAsString();
        long caseId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("caseId").asLong();
        String metadata="{\"customerId\":1,\"revision\":0,\"requestId\":\"metadata\",\"documentSlotKey\":\"HEIR_IDENTITY_CARD:h1\",\"fileName\":\"id.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":1024,\"storageReference\":\"fake://id.pdf\",\"status\":\"UPLOADED\"}";
        http.perform(post("/api/v1/c02/{caseId}/documents/metadata",caseId).contentType(MediaType.APPLICATION_JSON).content(metadata).header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","smoke-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        http.perform(get("/api/v1/c02/{caseId}/documents",caseId).param("customerId","1").param("requestId","read").header("X-Actor-Id",1).header("X-Actor-Role","CUSTOMER").header("X-Tool-Gateway-Token","smoke-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].documentSlotKey").value("HEIR_IDENTITY_CARD:h1"));
    }
}
