package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff JWT boundary for the AI review opinion. Decisions and notifications stay on the staff-decision route. */
@RestController
@RequestMapping("/api/v1/staff/c02/{caseId}/ai-review")
@ConditionalOnDataSource
public class C02StaffBriefController {
    private final C02StaffBriefService briefs;
    private final TaskBffPrincipal principals;

    public C02StaffBriefController(C02StaffBriefService briefs, TaskBffPrincipal principals) {
        this.briefs = briefs;
        this.principals = principals;
    }

    @GetMapping
    public ApiEnvelope<JsonNode> latest(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        briefs.requireReviewer(principals.staff(authentication));
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, briefs.latest(caseId).orElse(null));
    }

    @PostMapping
    public ApiEnvelope<JsonNode> generate(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestBody Generate body) {
        long staffId = principals.staff(authentication);
        briefs.requireReviewer(staffId);
        return ApiEnvelope.success(body.requestId(), "INHERITANCE_DEPOSIT", null, caseId, body.revision(), briefs.generate(caseId, body.revision(), staffId));
    }

    @GetMapping("/history")
    public ApiEnvelope<java.util.List<JsonNode>> history(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        briefs.requireReviewer(principals.staff(authentication));
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, briefs.history(caseId));
    }

    public record Generate(int revision, String requestId) { }
}
