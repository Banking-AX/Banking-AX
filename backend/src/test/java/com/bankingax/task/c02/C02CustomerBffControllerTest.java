package com.bankingax.task.c02;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

@WebMvcTest(C02CustomerBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class C02CustomerBffControllerTest {
    @Autowired MockMvc http;
    @MockBean C02Controller internal;
    @MockBean WorkflowStartService workflowStarts;
    @MockBean C02PowerOfAttorneyDraftService poaDrafts;

    @Test
    void startsInheritanceDepositForJwtCustomerOnlyAndNeverTakesCustomerIdFromBrowser() throws Exception {
        when(workflowStarts.start(org.mockito.ArgumentMatchers.any())).thenReturn(
                new WorkflowStartService.StartResult(91L, 92L, TaskCode.INHERITANCE_DEPOSIT, WorkItemStatus.ACTIVE));

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/customer/c02/work-items")
                        .contentType("application/json").content("{\"requestId\":\"c02-start-1\"}")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workItemId").value(92))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.workItemId").value(92));

        ArgumentCaptor<WorkflowStartService.StartCommand> command = ArgumentCaptor.forClass(WorkflowStartService.StartCommand.class);
        verify(workflowStarts).start(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals("c02-start-1", command.getValue().requestId());
        org.junit.jupiter.api.Assertions.assertEquals(42L, command.getValue().customerId());
        org.junit.jupiter.api.Assertions.assertEquals(TaskCode.INHERITANCE_DEPOSIT, command.getValue().taskCode());

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/customer/c02/work-items")
                        .contentType("application/json").content("{\"requestId\":\"c02-start-2\"}")
                        .with(jwt().jwt(token -> token.subject("99").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void prepareForwardsTheRoutedWorkItemAndDecedentInputToTheSpringC02Boundary() throws Exception {
        when(internal.prepare(eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ApiEnvelope.success("prepare-new", "INHERITANCE_DEPOSIT", 93L, null, null, null));

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/customer/c02/prepare")
                        .contentType("application/json")
                        .content("{\"workItemId\":93,\"requestId\":\"prepare-new\",\"registeredPhone\":null,"
                                + "\"decedent\":{\"name\":\"Synthetic Decedent\",\"birthDate\":null,\"deathDate\":\"2020-01-01\",\"depositTotal\":0},"
                                + "\"heirs\":[{\"personRef\":\"attending\",\"name\":\"Synthetic Applicant\",\"birthDate\":\"1997-04-12\",\"relationship\":\"child\",\"applicant\":true,\"higherOrderOrSubrogation\":false,\"visitStatus\":\"ATTENDING\",\"delegationMethod\":\"NONE\",\"delegationReceiptNo\":null},{\"personRef\":\"absent\",\"name\":\"Synthetic Delegator\",\"birthDate\":\"1994-01-03\",\"relationship\":\"child\",\"applicant\":false,\"higherOrderOrSubrogation\":false,\"visitStatus\":\"ABSENT\",\"delegationMethod\":\"DOCUMENT\",\"delegationReceiptNo\":null}],\"documentStatuses\":{}}")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());

        ArgumentCaptor<C02Controller.PrepareBody> body = ArgumentCaptor.forClass(C02Controller.PrepareBody.class);
        verify(internal).prepare(eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"), body.capture());
        org.junit.jupiter.api.Assertions.assertEquals(93L, body.getValue().workItemId());
        org.junit.jupiter.api.Assertions.assertEquals("Synthetic Decedent", body.getValue().decedent().name());
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.of(2020, 1, 1), body.getValue().decedent().deathDate());
    }

    @Test
    void customerOcrUsesJwtOwnerAndStaffIsRejected() throws Exception {
        when(internal.customerOcr(eq(55L), eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"), eq(42L), eq(3), eq("ocr-1")))
                .thenReturn(ApiEnvelope.success("ocr-1", "INHERITANCE_DEPOSIT", null, 55L, 3, null));
        http.perform(get("/api/v1/customer/c02/55/ocr").param("revision", "3").param("requestId", "ocr-1")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());
        verify(internal).customerOcr(55L, 42L, ActorRole.CUSTOMER, "server-only-token", 42L, 3, "ocr-1");
        http.perform(get("/api/v1/customer/c02/55/ocr").param("revision", "3").param("requestId", "ocr-2")
                        .with(jwt().jwt(token -> token.subject("99").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void powerOfAttorneyDraftUsesJwtCustomerAndReturnsPrivatePdf() throws Exception {
        when(poaDrafts.create(eq(42L), eq(55L), org.mockito.ArgumentMatchers.any())).thenReturn("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/customer/c02/55/power-of-attorney/draft")
                        .contentType("application/json")
                        .content("{\"delegatorPersonRef\":\"absent\",\"accountNumbers\":[\"99900000000001\"],\"depositProduct\":\"ordinary\",\"agentAddress\":\"agent address\",\"delegatorAddress\":\"delegator address\",\"delegatorRelationship\":\"child\",\"writtenDate\":\"2026-11-06\"}")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
        verify(poaDrafts).create(eq(42L), eq(55L), org.mockito.ArgumentMatchers.any());

        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/customer/c02/55/power-of-attorney/draft")
                        .contentType("application/json").content("{}")
                        .with(jwt().jwt(token -> token.subject("99").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }
}
