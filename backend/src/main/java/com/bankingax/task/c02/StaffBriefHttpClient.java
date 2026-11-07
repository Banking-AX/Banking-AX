package com.bankingax.task.c02;

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

/** Longer read timeout than the precheck: staff ask for this on demand and it may wait for an LLM. */
@Service
public class StaffBriefHttpClient implements StaffBriefPort {
    private final String baseUrl, token;
    private final ObjectMapper json;
    private final RestTemplate client;

    public StaffBriefHttpClient(@Value("${app.staff-agent-base-url:}") String baseUrl, @Value("${app.staff-agent-token:}") String token, ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.json = json;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(25000);
        this.client = new RestTemplate(requests);
    }

    @Override public JsonNode write(Map<String, Object> facts) {
        if (baseUrl.isBlank() || token.isBlank())
            throw new DomainException("STAFF_AGENT_UNAVAILABLE", "Staff Agent internal endpoint is not configured.", true);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Staff-Agent-Token", token);
            return json.readTree(client.postForObject(baseUrl.replaceAll("/$", "") + "/api/v1/staff-agent/case-brief",
                    new HttpEntity<>(Map.of("facts", facts), headers), String.class));
        } catch (RestClientException | java.io.IOException | IllegalArgumentException exception) {
            throw new DomainException("STAFF_AGENT_UNAVAILABLE", "Staff Agent request failed.", true);
        }
    }
}
