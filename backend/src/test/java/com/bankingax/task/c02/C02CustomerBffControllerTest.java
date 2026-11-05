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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(C02CustomerBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class C02CustomerBffControllerTest {
    @Autowired MockMvc http;
    @MockBean C02Controller internal;

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
}
