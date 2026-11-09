package com.bankingax.core.policychange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockBankPolicyProviderTest {
    private static final String SECRET = "signing-secret";
    private static final String BODY = "{\"nextCursor\":\"7\",\"events\":[{\"changeEventId\":\"E1\"}]}";

    private HttpServer server;
    private final AtomicReference<String> signature = new AtomicReference<>();
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private final AtomicReference<String> seenToken = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mock-bank/v1/policy-changes", exchange -> {
            seenQuery.set(exchange.getRequestURI().getQuery());
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Policy-Source-Token"));
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            if (signature.get() != null) exchange.getResponseHeaders().set("X-Policy-Signature", signature.get());
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private MockBankPolicyProvider provider() {
        return new MockBankPolicyProvider("http://127.0.0.1:" + server.getAddress().getPort(), "token", SECRET, new ObjectMapper());
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void aCorrectlySignedFeedIsReturnedWithItsCursor() throws Exception {
        signature.set(sign(SECRET, BODY));

        var feed = provider().fetch("5");

        assertThat(feed.events()).hasSize(1);
        assertThat(feed.nextCursor()).isEqualTo("7");
        assertThat(seenQuery.get()).contains("after=5");
        assertThat(seenToken.get()).isEqualTo("token");
    }

    @Test void aWrongSignatureRejectsTheWholeFeed() throws Exception {
        signature.set(sign("attacker-secret", BODY));
        assertThat(assertThrows(DomainException.class, () -> provider().fetch(null)).code()).isEqualTo("POLICY_SOURCE_SIGNATURE_INVALID");
    }

    @Test void aMissingSignatureRejectsTheWholeFeed() {
        assertThat(assertThrows(DomainException.class, () -> provider().fetch(null)).code()).isEqualTo("POLICY_SOURCE_SIGNATURE_INVALID");
    }

    @Test void anUnreachableSourceIsRetryable() {
        server.stop(0);
        var failure = assertThrows(DomainException.class, () -> provider().fetch(null));
        assertThat(failure.code()).isEqualTo("POLICY_SOURCE_UNAVAILABLE");
        assertThat(failure.retryable()).isTrue();
    }

    @Test void missingConfigurationIsReportedWithoutACall() {
        var provider = new MockBankPolicyProvider("", "", "", new ObjectMapper());
        assertThat(assertThrows(DomainException.class, () -> provider.fetch(null)).code()).isEqualTo("POLICY_SOURCE_NOT_CONFIGURED");
    }
}
