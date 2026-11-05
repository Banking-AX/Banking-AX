package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class B01SolapiSmsSenderTest {
    private static final String API_KEY = "test-api-key";
    private static final String API_SECRET = "test-api-secret-never-log";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private HttpServer server;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        responseStatus = 200;
        responseBody = success();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/v4/send-many/detail", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOfficialRequestAndBuildsValidHmacHeader() throws Exception {
        B01SmsSender.SendReceipt receipt = sender().send("010-1234-5678", "verification text");

        assertThat(receipt.provider()).isEqualTo("SOLAPI");
        assertThat(receipt.acceptanceStatus()).isEqualTo("ACCEPTED");
        CapturedRequest request = captured.get();
        assertThat(request.contentType()).isEqualTo("application/json");
        assertValidAuthorization(request.authorization());
        JsonNode body = json.readTree(request.body());
        assertThat(body.path("strict").asBoolean()).isTrue();
        assertThat(body.path("allowDuplicates").asBoolean()).isFalse();
        assertThat(body.path("showMessageList").asBoolean()).isTrue();
        assertThat(body.at("/messages/0/to").asText()).isEqualTo("01012345678");
        assertThat(body.at("/messages/0/from").asText()).isEqualTo("0212345678");
        assertThat(body.at("/messages/0/type").asText()).isEqualTo("SMS");
    }

    @Test
    void rejectsAuthenticationFailureWithoutLeakingCredentials() {
        responseStatus = 401;
        responseBody = "{\"errorCode\":\"Unauthorized\",\"errorMessage\":\"rejected\"}";
        assertSafeFailure(sender());
    }

    @Test
    void rejectsUnregisteredSenderAndInsufficientBalanceResponses() {
        responseStatus = 400;
        responseBody = "{\"errorCode\":\"InvalidSender\"}";
        assertSafeFailure(sender());

        responseStatus = 402;
        responseBody = "{\"errorCode\":\"NotEnoughBalance\"}";
        assertSafeFailure(sender());
    }

    @Test
    void rejectsPerMessageRegistrationFailureEvenWithHttpSuccess() {
        responseBody = "{\"failedMessageList\":[{\"statusCode\":\"3040\"}],\"messageList\":[]}";
        assertSafeFailure(sender());
    }

    @Test
    void rejectsNonAcceptedMessageStatus() {
        responseBody = "{\"failedMessageList\":[],\"messageList\":[{\"statusCode\":\"3000\"}]}";
        assertSafeFailure(sender());
    }

    @Test
    void mapsNetworkFailureToSafeAvailabilityError() {
        B01SolapiSmsSender disconnected = new B01SolapiSmsSender(API_KEY, API_SECRET, "02-1234-5678",
                "http://127.0.0.1:1/messages/v4/send-many/detail", json);
        assertSafeFailure(disconnected);
    }

    private B01SolapiSmsSender sender() {
        return new B01SolapiSmsSender(API_KEY, API_SECRET, "02-1234-5678",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/messages/v4/send-many/detail", json);
    }

    private void assertSafeFailure(B01SolapiSmsSender sender) {
        DomainException failure = assertThrows(DomainException.class,
                () -> sender.send("01012345678", "otp-must-not-leak"));
        assertThat(failure.code()).isEqualTo("SMS_DELIVERY_UNAVAILABLE");
        assertThat(failure.getMessage()).doesNotContain(API_KEY, API_SECRET, "otp-must-not-leak");
    }

    private void assertValidAuthorization(String authorization) throws Exception {
        assertThat(authorization).startsWith("HMAC-SHA256 ");
        Map<String, String> values = Arrays.stream(authorization.substring("HMAC-SHA256 ".length()).split(", "))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(part -> part[0], part -> part[1]));
        assertThat(values.get("apiKey")).isEqualTo(API_KEY);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.HexFormat.of().formatHex(mac.doFinal(
                (values.get("date") + values.get("salt")).getBytes(StandardCharsets.UTF_8)));
        assertThat(values.get("signature")).isEqualTo(expected);
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        captured.set(new CapturedRequest(exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String success() {
        return "{\"groupInfo\":{\"status\":\"SENDING\"},\"failedMessageList\":[],"
                + "\"messageList\":[{\"messageId\":\"test-message-id\",\"statusCode\":\"2000\","
                + "\"statusMessage\":\"accepted\"}]}";
    }

    private record CapturedRequest(String authorization, String contentType, String body) { }
}
