package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * STAFF-19 / RAG-10 / RAG-09: a staff member asks about the task's rules while reviewing a Case. The Case is only the
 * audit anchor; none of its data goes to the Agent. The history keeps which evidence was used, never the question.
 */
@Service
@ConditionalOnDataSource
public class C02PolicyQuestionService {
    private static final String TASK_CODE = "INHERITANCE_DEPOSIT";
    // Resident, account and phone numbers all contain a long digit run; rule questions ("3개월", "2008년") do not.
    private static final Pattern IDENTIFIER = Pattern.compile("\\d[\\d\\s-]{5,}\\d");

    private final JdbcTemplate jdbc;
    private final StaffPolicyQuestionPort agent;
    private final ObjectMapper json;

    public C02PolicyQuestionService(JdbcTemplate jdbc, StaffPolicyQuestionPort agent, ObjectMapper json) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.json = json;
    }

    public JsonNode ask(long staffId, long caseId, String rawQuestion, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) throw new DomainException("VALIDATION_ERROR", "requestId is required (max 90).");
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.length() < 2 || question.length() > 300) throw new DomainException("VALIDATION_ERROR", "The question needs 2 to 300 characters.");
        if (IDENTIFIER.matcher(question).find()) throw new DomainException("QUESTION_HAS_IDENTIFIER", "Remove personal numbers from the question.");
        Integer found = jdbc.queryForObject("select count(*) from service_case where id=? and case_type=?", Integer.class, caseId, TASK_CODE);
        if (found == null || found == 0) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        JsonNode answer = agent.ask(question, TASK_CODE);
        record(staffId, caseId, requestId, answer);
        return answer;
    }

    private void record(long staffId, long caseId, String requestId, JsonNode answer) {
        List<Map<String, String>> used = new ArrayList<>();
        for (JsonNode row : answer.path("citations")) used.add(Map.of("docId", row.path("docId").asText(""), "section", row.path("section").asText("")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", answer.path("status").asText(""));
        payload.put("method", answer.path("method").asText(""));
        payload.put("promptVersion", answer.path("promptVersion").asText(""));
        payload.put("retrievedAt", answer.path("retrievedAt").asText(""));
        payload.put("citations", used);
        try {
            jdbc.update("insert ignore into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_STAFF_POLICY_QUESTION',null,null,'STAFF',?,?,?)",
                    String.valueOf(caseId), staffId, requestId, json.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
