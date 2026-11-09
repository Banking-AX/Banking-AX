package com.bankingax.core.policychange;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Pulls the Mock bank feed and rejects the whole response unless its HMAC-SHA256 signature matches. */
@Component
@ConditionalOnProperty(name = "app.policy-change.provider", havingValue = "mock-bank", matchIfMissing = true)
public class MockBankPolicyProvider implements PolicyChangeProvider {
    static final String SOURCE_SYSTEM = "MOCK_BANK_POLICY";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final int PAGE_SIZE = 100;

    private final String baseUrl, token, signingSecret;
    private final ObjectMapper json;
    private final RestTemplate client;

    public MockBankPolicyProvider(@Value("${app.policy-change.source-base-url:}") String baseUrl,
                                  @Value("${app.policy-change.source-token:}") String token,
                                  @Value("${app.policy-change.source-signing-secret:}") String signingSecret,
                                  ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.signingSecret = signingSecret;
        this.json = json;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(5000);
        this.client = new RestTemplate(requests);
    }

    @Override public String sourceSystem() { return SOURCE_SYSTEM; }

    @Override public Feed fetch(String cursor) {
        if (baseUrl.isBlank() || token.isBlank() || signingSecret.isBlank())
            throw new DomainException("POLICY_SOURCE_NOT_CONFIGURED", "The bank change feed is not configured.");
        byte[] body;
        String signature;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Policy-Source-Token", token);
            ResponseEntity<byte[]> response = client.exchange(uri(cursor), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            body = response.getBody();
            signature = response.getHeaders().getFirst("X-Policy-Signature");
        } catch (RestClientException exception) {
            throw new DomainException("POLICY_SOURCE_UNAVAILABLE", "The bank change feed could not be reached.", true);
        }
        if (body == null || !validSignature(body, signature))
            throw new DomainException("POLICY_SOURCE_SIGNATURE_INVALID", "The bank change feed failed signature verification.");
        return parse(body, cursor);
    }

    private URI uri(String cursor) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl.replaceAll("/$", "") + "/mock-bank/v1/policy-changes")
                .queryParam("after", cursor == null ? "0" : cursor).queryParam("limit", PAGE_SIZE).build().toUri();
    }

    private boolean validSignature(byte[] body, String header) {
        if (header == null || !header.startsWith(SIGNATURE_PREFIX)) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, header.substring(SIGNATURE_PREFIX.length()).getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private Feed parse(byte[] body, String cursor) {
        try {
            JsonNode root = json.readTree(body);
            if (!root.path("events").isArray()) throw new IllegalArgumentException("events");
            List<JsonNode> events = new ArrayList<>();
            root.path("events").forEach(events::add);
            return new Feed(events, root.path("nextCursor").asText(cursor));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new DomainException("POLICY_SOURCE_RESPONSE_INVALID", "The bank change feed response is invalid.");
        }
    }
}
