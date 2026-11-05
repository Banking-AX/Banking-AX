package com.bankingax.core.notification;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import com.bankingax.core.tool.ActorRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The customer notification BFF derives the owner from the JWT; browsers never choose a customer id. */
@WebMvcTest(CustomerNotificationBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class CustomerNotificationBffControllerTest {
    @Autowired MockMvc http;
    @MockBean NotificationController internal;

    private static JwtRequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("role", "CUSTOMER")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void listForwardsFilterAndCursorForTheJwtCustomer() throws Exception {
        var summary = new NotificationController.Summary(5, 2);
        when(internal.list(42L, ActorRole.CUSTOMER, "server-only-token", true, 90L, 10, "n-1"))
                .thenReturn(ApiEnvelope.success("n-1", "NOTIFICATION", null, null, null, new NotificationController.Page(List.of(), false, summary)));
        http.perform(get("/api/v1/customer/notifications").param("unreadOnly", "true").param("beforeId", "90").param("limit", "10")
                        .param("requestId", "n-1").param("customerId", "7").with(customer("42")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.unread").value(2));
        verify(internal).list(42L, ActorRole.CUSTOMER, "server-only-token", true, 90L, 10, "n-1");
    }

    @Test
    void summaryAndReadAllUseTheJwtCustomer() throws Exception {
        var summary = new NotificationController.Summary(3, 0);
        when(internal.summary(42L, ActorRole.CUSTOMER, "server-only-token", "s-1"))
                .thenReturn(ApiEnvelope.success("s-1", "NOTIFICATION", null, null, null, summary));
        when(internal.readAll(42L, ActorRole.CUSTOMER, "server-only-token", "r-1"))
                .thenReturn(ApiEnvelope.success("r-1", "NOTIFICATION", null, null, null, new NotificationController.ReadAll(2, summary)));
        http.perform(get("/api/v1/customer/notifications/summary").param("requestId", "s-1").with(customer("42"))).andExpect(status().isOk());
        http.perform(post("/api/v1/customer/notifications/read-all").param("requestId", "r-1").with(customer("42")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.updated").value(2));
        verify(internal).readAll(42L, ActorRole.CUSTOMER, "server-only-token", "r-1");
    }

    @Test
    void staffAndAnonymousCannotReadCustomerNotifications() throws Exception {
        http.perform(get("/api/v1/customer/notifications/summary").param("requestId", "x")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "EMPLOYEE")).authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
        // The slice security chain has no bearer entry point, so anonymous is 403 here (401 AUTH_REQUIRED in the app).
        http.perform(get("/api/v1/customer/notifications").param("requestId", "y")).andExpect(status().isForbidden());
        verifyNoInteractions(internal);
    }
}
