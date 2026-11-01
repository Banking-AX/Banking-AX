package com.bankingax.core.auth;

import com.bankingax.core.api.DomainException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Derives task actors exclusively from a validated JWT, never request input. */
@Component
public class TaskBffPrincipal {
    public long customer(JwtAuthenticationToken authentication) {
        require(authentication, "CUSTOMER", "ADMIN");
        return subject(authentication);
    }

    public long staff(JwtAuthenticationToken authentication) {
        // AuthRole maps database STAFF to the JWT/authority value EMPLOYEE.
        require(authentication, "EMPLOYEE", "ADMIN");
        return subject(authentication);
    }

    private static void require(JwtAuthenticationToken authentication, String... permittedRoles) {
        String role = authentication.getToken().getClaimAsString("role");
        for (String permitted : permittedRoles) if (permitted.equals(role)) return;
        throw new DomainException("FORBIDDEN", "The authenticated role cannot use this task route.");
    }

    private static long subject(JwtAuthenticationToken authentication) {
        try {
            return Long.parseLong(authentication.getToken().getSubject());
        } catch (RuntimeException exception) {
            throw new DomainException("FORBIDDEN", "The authenticated subject is invalid.");
        }
    }
}
