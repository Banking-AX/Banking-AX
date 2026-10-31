package com.bankingax.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureMockMvc
@Import(AuthSecurityMySqlIT.SecurityProbeController.class)
@SpringBootTest(properties = {
        "app.auth.enabled=true",
        "app.auth.jwt-secret=test-only-jwt-secret-with-more-than-32-random-bytes",
        "app.auth.issuer=banking-ax-test",
        "app.auth.access-token-minutes=30",
        "app.auth.refresh-token-hours=24",
        "app.auth.cookie-secure=false",
        "app.tool-gateway-token=test-internal-tool-token"
})
class AuthSecurityMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_auth_it").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired RefreshTokenService refreshTokenService;

    @BeforeEach
    void seedUsers() {
        jdbc.update("delete from refresh_token");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,?)",
                1, "customer_demo", passwords.encode("demo1234"), "CUSTOMER", "김민수", true);
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,?)",
                101, "staff_demo", passwords.encode("staff-password"), "STAFF", "박지현", true);
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,?)",
                2, "inactive_demo", passwords.encode("demo1234"), "CUSTOMER", "비활성", false);
    }

    @Test
    void loginIssuesAccessAndHttpOnlyRefreshWhileDatabaseStoresOnlyHash() throws Exception {
        AuthSession session = login("customer_demo", "demo1234", 1, "CUSTOMER");
        assertThat(session.refresh().isHttpOnly()).isTrue();
        assertThat(session.refresh().getSecure()).isFalse();
        assertThat(session.refresh().getPath()).isEqualTo("/api/v1/auth");
        String storedHash = jdbc.queryForObject("select token_hash from refresh_token", String.class);
        assertThat(storedHash).hasSize(64).isEqualTo(RefreshTokenService.hash(session.refresh().getValue()));
        assertThat(storedHash).doesNotContain(session.refresh().getValue());
        http.perform(get("/api/v1/auth/me").header("Authorization", bearer(session.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    void refreshRotatesTokenAndPreservesOriginalAbsoluteExpiry() throws Exception {
        AuthSession first = login("customer_demo", "demo1234", 1, "CUSTOMER");
        Instant originalExpiry = absoluteExpiry(first.refresh());
        AuthSession rotated = refresh(first);
        assertThat(rotated.refresh().getValue()).isNotEqualTo(first.refresh().getValue());
        assertThat(absoluteExpiry(rotated.refresh())).isEqualTo(originalExpiry);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is null", Integer.class))
                .isEqualTo(1);
        http.perform(get("/api/v1/auth/me").header("Authorization", bearer(rotated.accessToken())))
                .andExpect(status().isOk());
    }

    @Test
    void reuseOfRotatedTokenIsRejectedAndRevokesTheReplacementFamily() throws Exception {
        AuthSession first = login("customer_demo", "demo1234", 1, "CUSTOMER");
        AuthSession rotated = refresh(first);
        refreshRequest(first).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_INVALID"));
        refreshRequest(rotated).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is null", Integer.class))
                .isZero();
    }

    @Test
    void concurrentRotationAllowsOnlyOneUseAndLeavesNoReusableFamilyAfterReuseDetection() throws Exception {
        AuthSession session = login("customer_demo", "demo1234", 1, "CUSTOMER");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<RefreshTokenService.RotationResult>> attempts = List.of(
                    executor.submit(() -> { start.await(); return refreshTokenService.rotate(session.refresh().getValue()); }),
                    executor.submit(() -> { start.await(); return refreshTokenService.rotate(session.refresh().getValue()); }));
            start.countDown();
            long successful = 0;
            for (Future<RefreshTokenService.RotationResult> attempt : attempts) {
                if (attempt.get().successful()) successful++;
            }
            assertThat(successful).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is null", Integer.class))
                .isZero();
    }

    @Test
    void expiredOrInactiveUserRefreshIsRejected() throws Exception {
        AuthSession expired = login("customer_demo", "demo1234", 1, "CUSTOMER");
        jdbc.update("update refresh_token set issued_at = utc_timestamp(6) - interval 25 hour, "
                + "absolute_expires_at = utc_timestamp(6) - interval 1 hour");
        refreshRequest(expired).andExpect(status().isUnauthorized());
        jdbc.update("delete from refresh_token");
        AuthSession inactive = login("customer_demo", "demo1234", 1, "CUSTOMER");
        jdbc.update("update `user` set is_active=false where id=1");
        refreshRequest(inactive).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshAndClearsCookiesEvenWithoutValidAccessToken() throws Exception {
        AuthSession session = login("customer_demo", "demo1234", 1, "CUSTOMER");
        MvcResult result = http.perform(post("/api/v1/auth/logout")
                        .cookie(session.refresh(), session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue()))
                .andExpect(status().isOk()).andReturn();
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is null", Integer.class))
                .isZero();
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(value -> value.startsWith(AuthController.REFRESH_COOKIE + "=")
                        && value.contains("Max-Age=0"));
    }

    @Test
    void missingTamperedRefreshAndMissingCsrfAreBlocked() throws Exception {
        AuthSession session = login("customer_demo", "demo1234", 1, "CUSTOMER");
        http.perform(post("/api/v1/auth/refresh").cookie(session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue()))
                .andExpect(status().isUnauthorized());
        Cookie tampered = copy(session.refresh(), "tampered-token");
        http.perform(post("/api/v1/auth/refresh").cookie(tampered, session.csrf())
                        .header("X-XSRF-TOKEN", session.csrf().getValue()))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/auth/refresh").cookie(session.refresh(), session.csrf()))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/auth/logout").cookie(session.refresh(), session.csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerAndEmployeeNamespacesEnforceDifferentAuthorities() throws Exception {
        AuthSession customer = login("customer_demo", "demo1234", 1, "CUSTOMER");
        AuthSession employee = login("staff_demo", "staff-password", 101, "STAFF");
        http.perform(get("/api/v1/customer/security-probe").header("Authorization", bearer(customer.accessToken())))
                .andExpect(status().isOk());
        http.perform(get("/api/v1/staff/security-probe").header("Authorization", bearer(customer.accessToken())))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/staff/security-probe").header("Authorization", bearer(employee.accessToken())))
                .andExpect(status().isOk());
        http.perform(get("/api/v1/customer/security-probe").header("Authorization", bearer(employee.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidLoginsAndInvalidAccessTokensAreRejectedWithoutAccountDisclosure() throws Exception {
        assertInvalidLogin("customer_demo", "wrong-password");
        assertInvalidLogin("missing-user", "demo1234");
        assertInvalidLogin("inactive_demo", "demo1234");
        http.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/auth/me").header("Authorization", bearer(expiredToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalToolTokenRemainsSeparateFromCustomerJwt() throws Exception {
        AuthSession customer = login("customer_demo", "demo1234", 1, "CUSTOMER");
        http.perform(get("/api/v1/tools/security-probe")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/tools/security-probe").header("Authorization", bearer(customer.accessToken())))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/tools/security-probe")
                        .header("X-Tool-Gateway-Token", "test-internal-tool-token"))
                .andExpect(status().isOk());
    }

    private AuthSession login(String loginId, String password, long userId, String apiRole) throws Exception {
        MvcResult result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody(loginId, password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.role").value(apiRole)).andReturn();
        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        Cookie refresh = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        Cookie csrf = result.getResponse().getCookie(AuthController.CSRF_COOKIE);
        assertThat(refresh).isNotNull();
        assertThat(csrf).isNotNull();
        return new AuthSession(root.path("data").path("accessToken").asText(), refresh, csrf);
    }

    private AuthSession refresh(AuthSession session) throws Exception {
        MvcResult result = refreshRequest(session).andExpect(status().isOk()).andReturn();
        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        Cookie replacement = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
        assertThat(replacement).isNotNull();
        return new AuthSession(root.path("data").path("accessToken").asText(), replacement, session.csrf());
    }

    private org.springframework.test.web.servlet.ResultActions refreshRequest(AuthSession session) throws Exception {
        return http.perform(post("/api/v1/auth/refresh").cookie(session.refresh(), session.csrf())
                .header("X-XSRF-TOKEN", session.csrf().getValue()));
    }

    private Instant absoluteExpiry(Cookie refresh) {
        return jdbc.queryForObject("select absolute_expires_at from refresh_token where token_hash=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), RefreshTokenService.hash(refresh.getValue()));
    }

    private void assertInvalidLogin(String loginId, String password) throws Exception {
        http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginBody(loginId, password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private String expiredToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("banking-ax-test").subject("1")
                .issuedAt(now.minusSeconds(120)).expiresAt(now.minusSeconds(60))
                .claim("role", "CUSTOMER").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static Cookie copy(Cookie source, String value) {
        Cookie copy = new Cookie(source.getName(), value);
        copy.setPath(source.getPath());
        copy.setHttpOnly(source.isHttpOnly());
        copy.setSecure(source.getSecure());
        return copy;
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record LoginBody(String loginId, String password) { }
    private record AuthSession(String accessToken, Cookie refresh, Cookie csrf) { }

    @RestController
    static class SecurityProbeController {
        @GetMapping({"/api/v1/customer/security-probe", "/api/v1/staff/security-probe",
                "/api/v1/tools/security-probe"})
        String probe() { return "ok"; }
    }
}
