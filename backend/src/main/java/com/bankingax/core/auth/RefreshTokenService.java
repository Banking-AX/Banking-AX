package com.bankingax.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
class RefreshTokenService {
    private static final int TOKEN_BYTES = 32;
    private final RefreshTokenRepository tokens;
    private final AuthUserRepository users;
    private final AuthService auth;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenService(RefreshTokenRepository tokens, AuthUserRepository users, AuthService auth,
                        AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.auth = auth;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    IssuedRefreshToken issue(long userId) {
        Instant now = clock.instant();
        Instant absoluteExpiry = now.plus(properties.refreshTokenTtl());
        String raw = newToken();
        tokens.save(RefreshTokenEntity.issue(
                userId, hash(raw), UUID.randomUUID().toString(), now, absoluteExpiry));
        return new IssuedRefreshToken(raw, absoluteExpiry);
    }

    @Transactional
    RotationResult rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return RotationResult.failure();
        Instant now = clock.instant();
        RefreshTokenEntity current = tokens.findByTokenHashForUpdate(hash(rawToken)).orElse(null);
        if (current == null) return RotationResult.failure();

        if (current.revokedAt != null) {
            tokens.revokeActiveFamily(current.familyId, now);
            return RotationResult.failure();
        }
        if (!current.absoluteExpiresAt.isAfter(now)) {
            current.revokedAt = now;
            return RotationResult.failure();
        }
        AuthUserEntity user = users.findById(current.userId).orElse(null);
        if (user == null || !user.active) {
            tokens.revokeActiveFamily(current.familyId, now);
            return RotationResult.failure();
        }

        String replacementRaw = newToken();
        RefreshTokenEntity replacement = tokens.saveAndFlush(RefreshTokenEntity.issue(
                current.userId, hash(replacementRaw), current.familyId, now, current.absoluteExpiresAt));
        current.revokedAt = now;
        current.replacedById = replacement.id;
        return RotationResult.success(
                auth.issueAccessToken(user), replacementRaw, current.absoluteExpiresAt);
    }

    @Transactional
    void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        Instant now = clock.instant();
        tokens.findByTokenHashForUpdate(hash(rawToken)).ifPresent(token -> {
            if (token.revokedAt == null) token.revokedAt = now;
        });
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record IssuedRefreshToken(String rawToken, Instant absoluteExpiresAt) {
        @Override public String toString() {
            return "IssuedRefreshToken[rawToken=[REDACTED], absoluteExpiresAt=" + absoluteExpiresAt + "]";
        }
    }

    record RotationResult(boolean successful, AuthService.LoginResult access,
                          String rawToken, Instant absoluteExpiresAt) {
        static RotationResult success(AuthService.LoginResult access, String rawToken, Instant expiry) {
            return new RotationResult(true, access, rawToken, expiry);
        }
        static RotationResult failure() {
            return new RotationResult(false, null, null, null);
        }
        @Override public String toString() {
            return "RotationResult[successful=" + successful + ", access=" + access
                    + ", rawToken=[REDACTED], absoluteExpiresAt=" + absoluteExpiresAt + "]";
        }
    }
}
