package com.bankingax.task.b02;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import com.bankingax.core.tool.ActorRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(B02CustomerBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class B02CustomerBffControllerTest {
    @Autowired MockMvc http;
    @MockBean B02Controller internal;
    @MockBean B02WorkflowService workflow;

    @Test
    void keepsWorkItemSeparateFromAccountAndUsesJwtCustomer() throws Exception {
        when(internal.p(eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"), any()))
                .thenReturn(ApiEnvelope.success("b02-1", "HOUSING_SAVINGS_CLOSE", null, null, null, null));
        http.perform(post("/api/v1/customer/b02/prepare")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b02-1\",\"account\":7,\"workItemId\":100,\"customer\":999,\"waive\":false}"))
                .andExpect(status().isOk());
        verify(internal).p(eq(42L), eq(ActorRole.CUSTOMER), eq("server-only-token"), any(B02Controller.Q.class));
    }

    @Test
    void resultReadUsesAuthenticatedCustomerAndWorkItem() throws Exception {
        when(workflow.result(42L, 100L)).thenReturn(new B02WorkflowService.StoredResult(
                100L, "DONE", "B2-R1", "1.7.1", null, null, null));
        http.perform(get("/api/v1/customer/b02/work-items/100/result")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workItemId").value(100))
                .andExpect(jsonPath("$.data.status").value("DONE"));
        verify(workflow).result(42L, 100L);
    }
}
