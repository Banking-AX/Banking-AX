package com.bankingax.core.chat;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpCustomerAgentClient implements CustomerAgentClient {
    private static final String PATH = "/api/v1/customer-agent/messages/start";

    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient http;

    HttpCustomerAgentClient(
            @Value("${app.customer-agent-base-url:}") String baseUrl,
            @Value("${app.customer-agent-token:}") String token,
            @Value("${app.customer-agent-timeout-seconds:20}") long timeoutSeconds,
            ObjectMapper json) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.token = token == null ? "" : token.strip();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public AgentResponse start(AgentRequest request) {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw new DomainException(
                    "CUSTOMER_AGENT_UNAVAILABLE", "Customer Agent is not configured.", true);
        }
        HttpRequest outbound;
        try {
            outbound = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Customer-Agent-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                    .build();
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new DomainException(
                    "CUSTOMER_AGENT_CONFIGURATION_INVALID", "Customer Agent request could not be prepared.");
        }

        try {
            HttpResponse<String> response = http.send(outbound, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new DomainException(
                        "CUSTOMER_AGENT_AUTH_FAILED", "Customer Agent authentication failed.");
            }
            if (response.statusCode() == 409 && isTrustedDuplicateConflict(response.body())) {
                throw new DomainException(
                        "DUPLICATE_REQUEST_CONFLICT", "operationRequestId was reused for another request.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DomainException(
                        "CUSTOMER_AGENT_UNAVAILABLE", "Customer Agent request failed.", true);
            }
            try {
                return json.readValue(response.body(), AgentResponse.class);
            } catch (JsonProcessingException exception) {
                throw new DomainException(
                        "CUSTOMER_AGENT_INVALID_RESPONSE", "Customer Agent returned an invalid response.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException(
                    "CUSTOMER_AGENT_UNAVAILABLE", "Customer Agent request was interrupted.", true);
        } catch (IOException exception) {
            throw new DomainException(
                    "CUSTOMER_AGENT_UNAVAILABLE", "Customer Agent is unavailable.", true);
        }
    }

    private boolean isTrustedDuplicateConflict(String body) {
        try {
            return "DUPLICATE_REQUEST_CONFLICT".equals(json.readTree(body).path("detail").asText());
        } catch (JsonProcessingException exception) {
            return false;
        }
    }
}
