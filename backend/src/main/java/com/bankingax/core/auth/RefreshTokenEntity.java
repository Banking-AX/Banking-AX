package com.bankingax.core.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refresh_token")
class RefreshTokenEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "user_id", nullable = false)
    long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64,
            columnDefinition = "char(64)")
    String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36, columnDefinition = "char(36)")
    String familyId;

    @Column(name = "issued_at", nullable = false)
    Instant issuedAt;

    @Column(name = "absolute_expires_at", nullable = false)
    Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    Instant revokedAt;

    @Column(name = "replaced_by_id")
    Long replacedById;

    static RefreshTokenEntity issue(long userId, String hash, String familyId,
                                    Instant issuedAt, Instant absoluteExpiresAt) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.userId = userId;
        token.tokenHash = hash;
        token.familyId = familyId;
        token.issuedAt = issuedAt;
        token.absoluteExpiresAt = absoluteExpiresAt;
        return token;
    }
}
