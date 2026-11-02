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
    private static final URI ENDPOINT = URI.create("https://api.solapi.com/messages/v4/send-many/detail");
    private final String apiKey;
    private final String apiSecret;
    private final String senderNumber;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    B01SolapiSmsSender(@Value("${solapi.api-key:}") String apiKey,
                       @Value("${solapi.api-secret:}") String apiSecret,
                       @Value("${solapi.sender-number:}") String senderNumber,
                       ObjectMapper json) {
        this.apiKey = required(apiKey); this.apiSecret = required(apiSecret);
        this.senderNumber = digits(required(senderNumber)); this.json = json;
    }

    @Override public void send(String phone, String text) {
        try {
            String body = json.writeValueAsString(Map.of("messages", java.util.List.of(Map.of(
                    "to", digits(phone), "from", senderNumber, "text", text, "type", "SMS"))));
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(8))
                    .header("Authorization", authorization()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw unavailable();
            var result = json.readTree(response.body());
            if (!result.path("failedMessageList").isEmpty() || result.path("messageList").isEmpty())
                throw unavailable();
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
    private static String digits(String value) { return value.replaceAll("[^0-9]", ""); }
    private static DomainException unavailable() {
        return new DomainException("SMS_DELIVERY_UNAVAILABLE", "The verification message could not be sent.", true);
    }
}
