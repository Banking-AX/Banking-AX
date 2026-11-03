package com.bankingax.core.reservation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CustomerReservationBffControllerTest {
    @Test
    void customer_identity_is_derived_from_jwt_and_staff_role_is_rejected() {
        VisitReservationController internal = mock(VisitReservationController.class);
        CustomerReservationBffController bff = new CustomerReservationBffController(
                internal, new TaskBffPrincipal(), "server-only-gateway-token");
        var body = new VisitReservationController.Create("bff-request", 55, 7,
                java.time.OffsetDateTime.parse("2026-12-01T10:00:00+09:00"));
        when(internal.create(anyLong(), any(), any(), any())).thenReturn(null);

        bff.create(authentication("123", "CUSTOMER"), body);
        verify(internal).create(eq(123L), eq(ActorRole.CUSTOMER), eq("server-only-gateway-token"), eq(body));

        assertThrows(DomainException.class, () -> bff.create(authentication("456", "EMPLOYEE"), body));
    }

    private static JwtAuthenticationToken authentication(String subject, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-only-token")
                .header("alg", "none")
                .subject(subject)
                .claim("role", role)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
