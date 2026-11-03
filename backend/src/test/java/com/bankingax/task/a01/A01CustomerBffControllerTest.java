package com.bankingax.task.a01;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.ToolGatewayController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(A01CustomerBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class A01CustomerBffControllerTest {
    @Autowired MockMvc http;
    @MockBean ToolGatewayController tools;
    @MockBean A01WorkflowService workflow;

    @Test
    void derivesCustomerFromJwtAndDoesNotAcceptSpoofedIdentity() throws Exception {
        when(tools.transactionSearch(eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"), any()))
                .thenReturn(ApiEnvelope.success("a01-1", "TRANSACTION_SEARCH", 100L, null, null, null));

        http.perform(post("/api/v1/customer/a01/transaction-search")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a01-1\",\"workItemId\":100,\"accountId\":7,\"customerId\":999,\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-02\",\"page\":0,\"size\":20}"))
                .andExpect(status().isOk());

        verify(tools).transactionSearch(eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"),
                any(TransactionSearchRequest.class));
    }

    @Test
    void staffJwtCannotUseCustomerA01Route() throws Exception {
        http.perform(post("/api/v1/customer/a01/transaction-search")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void resultReadUsesJwtCustomerAndOwnedWorkItem() throws Exception {
        var result = new TransactionSearchResponse(7L, "****0007", "주거래통장", "Asia/Seoul", java.util.List.of(), 0, 20, 0, 0);
        when(workflow.result(42L, 100L)).thenReturn(result);
        http.perform(get("/api/v1/customer/a01/work-items/100/result")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.taskCode").value("TRANSACTION_SEARCH"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workItemId").value(100))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.accountId").value(7));
        verify(workflow).result(42L, 100L);
    }

    @Test
    void resultReadRequiresCustomerAuthentication() throws Exception {
        http.perform(get("/api/v1/customer/a01/work-items/100/result"))
                .andExpect(status().isForbidden());
    }
}
