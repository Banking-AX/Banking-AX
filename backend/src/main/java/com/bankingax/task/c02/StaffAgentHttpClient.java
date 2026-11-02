package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/** Minimal internal HTTP adapter. No customer credentials or OTP values cross this boundary. */
@Service
@Profile("!e2e")
public class StaffAgentHttpClient implements StaffAgentPort {
    private final String baseUrl, token; private final ObjectMapper mapper; private final RestTemplate client;
    public StaffAgentHttpClient(@Value("${app.staff-agent-base-url:}") String baseUrl, @Value("${app.staff-agent-token:}") String token, ObjectMapper mapper) {
        this.baseUrl=baseUrl; this.token=token; this.mapper=mapper;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(3000); requests.setReadTimeout(5000);
        this.client = new RestTemplate(requests);
    }
    @Override public StaffAgentResponse check(String requestId,long caseId,int revision,Map<String,Object> caseSnapshot) {
        if(baseUrl.isBlank() || token.isBlank()) throw new DomainException("STAFF_AGENT_UNAVAILABLE","Staff Agent internal endpoint is not configured.",true);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Staff-Agent-Token", token);
            ResponseEntity<String> response = client.postForEntity(
                baseUrl.replaceAll("/$", "") + "/api/v1/staff-agent/check",
                new HttpEntity<>(Map.of("requestId",requestId,"caseId",caseId,"revision",revision,"taskCode","INHERITANCE_DEPOSIT","caseSnapshot",caseSnapshot), headers),
                String.class);
            Map<String,Object> decoded=mapper.readValue(response.getBody(),new TypeReference<>(){});
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)decoded.getOrDefault("reviewItems",List.of());
            @SuppressWarnings("unchecked") Map<String,Object> error=(Map<String,Object>)decoded.get("error");
            return new StaffAgentResponse((String)decoded.get("status"),(String)decoded.get("reviewStatus"),items,error);
        } catch (DomainException exception) { throw exception;
        } catch (Exception exception) { throw new DomainException("STAFF_AGENT_UNAVAILABLE","Staff Agent request failed ("+exception.getClass().getSimpleName()+").",true); }
    }
}
