package com.bankingax.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bankingax.core.api.DomainException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpCustomerAgentClientTest {
    @Test
    void sendsTheDedicatedServerTokenAndExactCustomerAgentContract() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> connection = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/customer-agent/messages/start", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Customer-Agent-Token"));
            body.set(json.readTree(exchange.getRequestBody()));
            protocol.set(exchange.getProtocol());
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            connection.set(exchange.getRequestHeaders().getFirst("Connection"));
            byte[] response = ("{\"requestId\":\"op-1\",\"customerId\":1,"
                    + "\"outcome\":\"INFORMATION_ONLY\",\"classification\":{},"
                    + "\"workflow\":null,\"loanInquiry\":null,\"executionStatus\":null,"
                    + "\"answer\":\"안내\",\"answerStatus\":\"GENERATED\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpCustomerAgentClient client = new HttpCustomerAgentClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "dedicated-test-token", 2, json);

            CustomerAgentClient.AgentResponse response = client.start(
                    new CustomerAgentClient.AgentRequest(
                            "op-1", 1, 77, "내 대출정보 조회해 줘", List.of("CUSTOMER: 이전 질문")));

            assertThat(response.answer()).isEqualTo("안내");
            assertThat(token.get()).isEqualTo("dedicated-test-token");
            assertThat(body.get().path("requestId").asText()).isEqualTo("op-1");
            assertThat(body.get().path("customerId").asLong()).isEqualTo(1);
            assertThat(body.get().path("chatRoomId").asLong()).isEqualTo(77);
            assertThat(protocol.get()).isEqualTo("HTTP/1.1");
            assertThat(upgrade.get()).isNull();
            assertThat(connection.get()).isNull();
            assertThat(body.get().path("recentMessages").get(0).asText())
                    .isEqualTo("CUSTOMER: 이전 질문");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesOnlyTheTrustedDuplicateConflictFromCustomerAgent() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/customer-agent/messages/start", exchange -> {
            byte[] response = "{\"detail\":\"DUPLICATE_REQUEST_CONFLICT\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpCustomerAgentClient client = new HttpCustomerAgentClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "dedicated-test-token", 2, json);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.start(
                            new CustomerAgentClient.AgentRequest(
                                    "op-conflict", 1, 77, "질문", List.of())))
                    .isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).code())
                    .isEqualTo("DUPLICATE_REQUEST_CONFLICT");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deserializesValidatedB01ReconnectAction() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        String responseBody = "{\"requestId\":\"op-b01\",\"customerId\":1,"
                + "\"outcome\":\"B01_RECONNECT_CONFIRMATION_REQUIRED\","
                + "\"classification\":{},\"workflow\":null,\"loanInquiry\":null,"
                + "\"executionStatus\":null,\"taskAction\":null,"
                + "\"b01ReconnectAction\":{\"type\":\"SELECT_B01_RECONNECT\","
                + "\"allowStartNew\":true,\"candidates\":["
                + "{\"workItemId\":3,\"status\":\"ON_HOLD\","
                + "\"originalChatRoomId\":1,\"originalChatRoomAvailable\":true}]},"
                + "\"answer\":\"진행 중인 업무가 있습니다.\","
                + "\"answerStatus\":\"GENERATED\"}";

        CustomerAgentClient.AgentResponse response = callAgent(json, responseBody, "op-b01");

        assertThat(response.b01ReconnectAction().allowStartNew()).isTrue();
        assertThat(response.b01ReconnectAction().candidates())
                .extracting(CustomerAgentClient.B01ReconnectCandidate::workItemId)
                .containsExactly(3L);
    }

    @Test
    void rejectsInvalidB01ReconnectCandidate() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        String responseBody = "{\"requestId\":\"op-b01-invalid\",\"customerId\":1,"
                + "\"outcome\":\"B01_RECONNECT_CONFIRMATION_REQUIRED\","
                + "\"classification\":{},\"workflow\":null,\"loanInquiry\":null,"
                + "\"executionStatus\":null,\"taskAction\":null,"
                + "\"b01ReconnectAction\":{\"type\":\"SELECT_B01_RECONNECT\","
                + "\"allowStartNew\":false,\"candidates\":["
                + "{\"workItemId\":18,\"status\":\"DONE\","
                + "\"originalChatRoomId\":5,\"originalChatRoomAvailable\":true}]},"
                + "\"answer\":\"진행 중인 업무가 있습니다.\","
                + "\"answerStatus\":\"GENERATED\"}";

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> callAgent(json, responseBody, "op-b01-invalid"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code())
                .isEqualTo("CUSTOMER_AGENT_INVALID_RESPONSE");
    }

    @Test
    void treatsAnUntrustedConflictBodyAsAnUpstreamAvailabilityFailure() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/customer-agent/messages/start", exchange -> {
            byte[] response = "{\"detail\":\"UNRELATED_CONFLICT\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpCustomerAgentClient client = new HttpCustomerAgentClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "dedicated-test-token", 2, json);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.start(
                            new CustomerAgentClient.AgentRequest(
                                    "op-other-conflict", 1, 77, "질문", List.of())))
                    .isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).code())
                    .isEqualTo("CUSTOMER_AGENT_UNAVAILABLE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsCustomerAgentAuthenticationFailureToTheSafeAuthError() throws Exception {
        assertUpstreamError(401, "{}", "CUSTOMER_AGENT_AUTH_FAILED");
    }

    @Test
    void mapsAnInvalidSuccessfulResponseToBadGatewayContract() throws Exception {
        assertUpstreamError(200, "not-json", "CUSTOMER_AGENT_INVALID_RESPONSE");
    }

    private void assertUpstreamError(int status, String responseBody, String expectedCode) throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/customer-agent/messages/start", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpCustomerAgentClient client = new HttpCustomerAgentClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "dedicated-test-token", 2, json);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.start(
                            new CustomerAgentClient.AgentRequest(
                                    "op-error", 1, 77, "질문", List.of())))
                    .isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).code())
                    .isEqualTo(expectedCode);
        } finally {
            server.stop(0);
        }
    }

    private CustomerAgentClient.AgentResponse callAgent(
            ObjectMapper json, String responseBody, String requestId) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/customer-agent/messages/start", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpCustomerAgentClient client = new HttpCustomerAgentClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "dedicated-test-token", 2, json);
            return client.start(new CustomerAgentClient.AgentRequest(
                    requestId, 1, 77, "질문", List.of()));
        } finally {
            server.stop(0);
        }
    }
}
