package com.bankingax.core.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.time.Instant;

class AuthSecretRedactionTest {
    @Test
    void requestAndResponseToStringDoNotExposePasswordOrJwt() {
        String request = new AuthController.LoginRequest("customer_demo", "plain-secret").toString();
        String response = new AuthService.LoginResult(
                "signed.jwt.value", "Bearer", 1800, 1, "김민수", "CUSTOMER").toString();
        String refresh = new RefreshTokenService.IssuedRefreshToken(
                "raw-refresh-token", Instant.parse("2026-11-02T00:00:00Z")).toString();

        assertFalse(request.contains("plain-secret"));
        assertFalse(response.contains("signed.jwt.value"));
        assertTrue(request.contains("[REDACTED]"));
        assertTrue(response.contains("[REDACTED]"));
        assertFalse(refresh.contains("raw-refresh-token"));
        assertTrue(refresh.contains("[REDACTED]"));
    }
}
