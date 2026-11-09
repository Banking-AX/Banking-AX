package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** STAFF-19. Same grant as the rest of the pre-review screens. */
@RestController
@ConditionalOnDataSource
public class C02PolicyQuestionController {
    private final C02PolicyQuestionService questions;
    private final TaskBffPrincipal principals;
    private final C02ReviewerAccess access;

    public C02PolicyQuestionController(C02PolicyQuestionService questions, TaskBffPrincipal principals, C02ReviewerAccess access) {
        this.questions = questions;
        this.principals = principals;
        this.access = access;
    }

    @PostMapping("/api/v1/staff/c02/{caseId}/policy-question")
    public ApiEnvelope<JsonNode> ask(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestBody Question body) {
        long staffId = principals.staff(authentication);
        access.require(staffId);
        return ApiEnvelope.success(body.requestId(), "INHERITANCE_DEPOSIT", null, caseId, null, questions.ask(staffId, caseId, body.question(), body.requestId()));
    }

    public record Question(String question, String requestId) { }
}
