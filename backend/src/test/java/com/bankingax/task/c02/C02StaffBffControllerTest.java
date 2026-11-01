package com.bankingax.task.c02;

import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(C02StaffBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class C02StaffBffControllerTest {
    @Autowired MockMvc http;
    @MockBean C02Controller internal;

    @Test
    void staffJwtCanReadStaffCaseButCustomerJwtIsRejected() throws Exception {
        when(internal.staffCase(eq(55L), eq(ActorRole.STAFF), eq("server-only-token"), eq("staff-1")))
                .thenReturn(ApiEnvelope.success("staff-1", "INHERITANCE_DEPOSIT", null, 55L, null, null));

        http.perform(get("/api/v1/staff/c02/55/staff-case").param("requestId", "staff-1")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk());
        verify(internal).staffCase(55L, ActorRole.STAFF, "server-only-token", "staff-1");

        http.perform(get("/api/v1/staff/c02/55/staff-case").param("requestId", "staff-2")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }
}
