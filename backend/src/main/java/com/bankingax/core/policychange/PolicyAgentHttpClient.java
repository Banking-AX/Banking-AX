package com.bankingax.core.policychange;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Longer read timeout than the Case pre-check: this path may wait for an LLM, and staff trigger it explicitly. */
@Service
public class PolicyAgentHttpClient implements PolicyAgentPort {
    private final String baseUrl, token;
    private final ObjectMapper json;
    private final RestTemplate client;

    public PolicyAgentHttpClient(@Value("${app.staff-agent-base-url:}") String baseUrl,
                                 @Value("${app.staff-agent-token:}") String token, ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.json = json;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(25000);
        this.client = new RestTemplate(requests);
    }

    @Override public JsonNode analyze(Map<String, Object> request) {
        if (baseUrl.isBlank() || token.isBlank())
            throw new DomainException("STAFF_AGENT_UNAVAILABLE", "Staff Agent internal endpoint is not configured.", true);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Staff-Agent-Token", token);
            String body = client.postForObject(baseUrl.replaceAll("/$", "") + "/api/v1/staff-agent/policy-impact",
                    new HttpEntity<>(request, headers), String.class);
            return json.readTree(body);
        } catch (RestClientException | java.io.IOException | IllegalArgumentException exception) {
            throw new DomainException("STAFF_AGENT_UNAVAILABLE", "Staff Agent request failed.", true);
        }
    }
}
