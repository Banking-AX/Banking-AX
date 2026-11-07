package com.bankingax.core.policychange;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
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

/** Staff JWT boundary. There is deliberately no route for an Agent or the bank to write a decision. */
@RestController
@RequestMapping("/api/v1/staff/policy-changes")
@ConditionalOnDataSource
public class StaffPolicyChangeController {
    private static final int REQUEST_ID_LIMIT = 64;

    private final PolicyChangeIngestService ingest;
    private final PolicyChangeReviewService review;
    private final TaskBffPrincipal principals;

    public StaffPolicyChangeController(PolicyChangeIngestService ingest, PolicyChangeReviewService review, TaskBffPrincipal principals) {
        this.ingest = ingest;
        this.review = review;
        this.principals = principals;
    }

    @GetMapping
    public ApiEnvelope<PolicyChangeReviewService.Overview> overview(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        return ok(requestId, review.overview(principals.staff(authentication)));
    }

    @GetMapping("/notices")
    public ApiEnvelope<PolicyChangeReviewService.Notices> notices(JwtAuthenticationToken authentication,
            @RequestParam String taskCode, @RequestParam(required = false) Long caseId, @RequestParam String requestId) {
        return ok(requestId, review.notices(principals.staff(authentication), taskCode, caseId));
    }

    @GetMapping("/{id}")
    public ApiEnvelope<PolicyChangeReviewService.Detail> detail(JwtAuthenticationToken authentication,
            @PathVariable long id, @RequestParam String requestId) {
        return ok(requestId, review.detail(principals.staff(authentication), id));
    }

    @PostMapping("/sync")
    public ApiEnvelope<PolicyChangeIngestService.SyncResult> sync(JwtAuthenticationToken authentication, @RequestBody RequestOnly body) {
        long staffId = principals.staff(authentication);
        review.requireReviewer(staffId);
        return ok(body.requestId(), ingest.sync(staffId, valid(body.requestId())));
    }

    @PostMapping("/{id}/analysis")
    public ApiEnvelope<JsonNode> analyze(JwtAuthenticationToken authentication, @PathVariable long id, @RequestBody RequestOnly body) {
        return ok(body.requestId(), review.analyze(principals.staff(authentication), id, valid(body.requestId())));
    }

    @PostMapping("/{id}/decision")
    public ApiEnvelope<PolicyChangeReviewService.DecisionView> decide(JwtAuthenticationToken authentication,
            @PathVariable long id, @RequestBody DecisionBody body) {
        valid(body.requestId());
        return ok(body.requestId(), review.decide(principals.staff(authentication), id, body.decision(), body.note()));
    }

    private static <T> ApiEnvelope<T> ok(String requestId, T data) {
        return ApiEnvelope.success(requestId, null, null, null, null, data);
    }

    private static String valid(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > REQUEST_ID_LIMIT)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 64 characters is required.");
        return requestId;
    }

    public record RequestOnly(String requestId) { }
    public record DecisionBody(String requestId, PolicyChangeReviewService.Decision decision, String note) { }
}
