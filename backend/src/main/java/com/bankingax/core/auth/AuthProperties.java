package com.bankingax.core.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(boolean enabled, String jwtSecret, String issuer, long accessTokenMinutes,
                             long refreshTokenHours, boolean cookieSecure) {
    public AuthProperties {
        jwtSecret = jwtSecret == null ? "" : jwtSecret;
        issuer = issuer == null || issuer.isBlank() ? "banking-ax" : issuer;
        if (accessTokenMinutes <= 0 || accessTokenMinutes > Duration.ofDays(1).toMinutes()) {
            throw new IllegalStateException("APP_JWT_ACCESS_TOKEN_MINUTES must be between 1 and 1440.");
        }
        if (refreshTokenHours <= 0 || refreshTokenHours > Duration.ofDays(7).toHours()) {
            throw new IllegalStateException("APP_REFRESH_TOKEN_HOURS must be between 1 and 168.");
        }
    }

    public Duration accessTokenTtl() {
        return Duration.ofMinutes(accessTokenMinutes);
    }

    public Duration refreshTokenTtl() {
        return Duration.ofHours(refreshTokenHours);
    }
}
