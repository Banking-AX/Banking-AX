package com.bankingax.core.auth;

import com.bankingax.core.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
class AuthController {
    static final String REFRESH_COOKIE = "BANKING_AX_REFRESH";
    static final String CSRF_COOKIE = "XSRF-TOKEN";
    private final AuthService auth;
    private final RefreshTokenService refreshTokens;
    private final AuthProperties properties;
    private final Clock clock;

    AuthController(AuthService auth, RefreshTokenService refreshTokens,
                   AuthProperties properties, Clock clock) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/login")
    ResponseEntity<ApiEnvelope<AuthService.LoginResult>> login(
            @RequestBody LoginRequest request, CsrfToken csrfToken) {
        String requestId = UUID.randomUUID().toString();
        AuthService.LoginResult result = auth.login(request.loginId(), request.password());
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokens.issue(result.userId());
        csrfToken.getToken();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                        refreshCookie(refresh.rawToken(), refresh.absoluteExpiresAt()).toString())
                .body(ApiEnvelope.success(requestId, "AUTH", null, null, null, result));
    }

    @PostMapping("/refresh")
    ResponseEntity<ApiEnvelope<AuthService.LoginResult>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String rawToken) {
        RefreshTokenService.RotationResult rotation = refreshTokens.rotate(rawToken);
        if (!rotation.successful()) throw new RefreshTokenFailureException();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                        refreshCookie(rotation.rawToken(), rotation.absoluteExpiresAt()).toString())
                .body(ApiEnvelope.success(UUID.randomUUID().toString(), "AUTH", null, null, null,
                        rotation.access()));
    }

    @GetMapping("/me")
    ApiEnvelope<AuthService.UserView> me(JwtAuthenticationToken authentication) {
        return ApiEnvelope.success(UUID.randomUUID().toString(), "AUTH", null, null, null,
                auth.me(parseSubject(authentication.getToken().getSubject())));
    }

    @PostMapping("/logout")
    ResponseEntity<ApiEnvelope<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String rawToken,
            HttpServletResponse response) {
        refreshTokens.revoke(rawToken);
        response.addHeader(HttpHeaders.SET_COOKIE, deleteCookie(REFRESH_COOKIE, true).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, deleteCookie(CSRF_COOKIE, false).toString());
        return ResponseEntity.ok(ApiEnvelope.success(
                UUID.randomUUID().toString(), "AUTH", null, null, null, null));
    }

    private ResponseCookie refreshCookie(String rawToken, Instant absoluteExpiry) {
        Duration remaining = Duration.between(clock.instant(), absoluteExpiry);
        if (remaining.isNegative()) remaining = Duration.ZERO;
        return ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/api/v1/auth").maxAge(remaining).build();
    }

    private ResponseCookie deleteCookie(String name, boolean httpOnly) {
        return ResponseCookie.from(name, "").httpOnly(httpOnly)
                .secure(properties.cookieSecure()).sameSite("Lax")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
    }

    private static long parseSubject(String subject) {
        try {
            return Long.parseLong(subject);
        } catch (RuntimeException exception) {
            throw new AuthFailureException();
        }
    }

    record LoginRequest(String loginId, String password) {
        @Override
        public String toString() {
            return "LoginRequest[loginId=" + loginId + ", password=[REDACTED]]";
        }
    }
}
