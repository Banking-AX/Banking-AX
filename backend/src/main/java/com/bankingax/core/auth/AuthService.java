package com.bankingax.core.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
class AuthService {
    private final AuthUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyHash;

    AuthService(AuthUserRepository users, PasswordEncoder passwords, JwtEncoder jwtEncoder,
                AuthProperties properties, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    LoginResult login(String loginId, String password) {
        String normalized = loginId == null ? "" : loginId.trim();
        AuthUserEntity user = users.findByLoginId(normalized).orElse(null);
        String candidateHash = user == null ? dummyHash : user.passwordHash;
        boolean passwordMatches = password != null && passwords.matches(password, candidateHash);
        if (user == null || !passwordMatches || !user.active) throw new AuthFailureException();

        return issueAccessToken(user);
    }

    LoginResult issueAccessToken(AuthUserEntity user) {
        AuthRole role = AuthRole.fromDatabase(user.role);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(user.id))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", role.authorityValue())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        UserView view = view(user, role);
        return new LoginResult(token, "Bearer", properties.accessTokenTtl().toSeconds(),
                view.userId(), view.name(), view.role());
    }

    @Transactional(readOnly = true)
    UserView me(long userId) {
        AuthUserEntity user = users.findById(userId).filter(candidate -> candidate.active)
                .orElseThrow(AuthFailureException::new);
        return view(user, AuthRole.fromDatabase(user.role));
    }

    private static UserView view(AuthUserEntity user, AuthRole role) {
        return new UserView(user.id, user.name, role.apiValue());
    }

    record UserView(long userId, String name, String role) { }
    record LoginResult(String accessToken, String tokenType, long expiresIn,
                       long userId, String name, String role) {
        @Override
        public String toString() {
            return "LoginResult[accessToken=[REDACTED], tokenType=" + tokenType
                    + ", expiresIn=" + expiresIn + ", userId=" + userId
                    + ", name=" + name + ", role=" + role + "]";
        }
    }
}
