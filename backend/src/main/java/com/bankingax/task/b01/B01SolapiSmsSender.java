package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "solapi")
final class B01SolapiSmsSender implements B01SmsSender {
    private static final String DEFAULT_ENDPOINT = "https://api.solapi.com/messages/v4/send-many/detail";
    private final String apiKey;
    private final String apiSecret;
    private final String senderNumber;
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    B01SolapiSmsSender(@Value("${solapi.api-key:}") String apiKey,
                       @Value("${solapi.api-secret:}") String apiSecret,
                       @Value("${solapi.sender-number:}") String senderNumber,
                       @Value("${solapi.api-endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
                       ObjectMapper json) {
        this.apiKey = required(apiKey); this.apiSecret = required(apiSecret);
        this.senderNumber = number(required(senderNumber), true);
        this.endpoint = endpoint(required(endpoint)); this.json = json;
    }

    @Override public SendReceipt send(String phone, String text) {
        try {
            String body = json.writeValueAsString(Map.of("messages", java.util.List.of(Map.of(
                    "to", number(phone, false), "from", senderNumber, "text", text, "type", "SMS")),
                    "strict", true, "allowDuplicates", false, "showMessageList", true));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                    .header("Authorization", authorization()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw unavailable();
            var result = json.readTree(response.body());
            if (!result.path("failedMessageList").isEmpty() || result.path("messageList").isEmpty())
                throw unavailable();
            var accepted = result.path("messageList").get(0);
            if (accepted == null || !"2000".equals(accepted.path("statusCode").asText()))
                throw unavailable();
            return SendReceipt.accepted("SOLAPI");
        } catch (DomainException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
    }

    private String authorization() throws Exception {
        String date = Instant.now().toString();
        String salt = UUID.randomUUID().toString().replace("-", "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
        return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s"
                .formatted(apiKey, date, salt, signature);
    }

    private static String required(String value) {
        if (value == null || value.isBlank())
            throw new IllegalStateException("SOLAPI credentials and sender number are required when provider=solapi");
        return value.trim();
    }
    private static URI endpoint(String value) {
        URI uri = URI.create(value);
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || loopback) || uri.getHost() == null)
            throw new IllegalStateException("SOLAPI endpoint must use HTTPS (loopback HTTP is test-only)");
        return uri;
    }
    private static String number(String value, boolean configuration) {
        String normalized = value == null ? "" : value.replaceAll("[^0-9]", "");
        int minimum = configuration ? 8 : 10;
        if (normalized.length() < minimum || normalized.length() > 11) {
            if (configuration) throw new IllegalStateException("A valid registered SOLAPI sender number is required");
            throw unavailable();
        }
        return normalized;
    }
    private static DomainException unavailable() {
        return new DomainException("SMS_DELIVERY_UNAVAILABLE", "The verification message could not be sent.", true);
    }
}
