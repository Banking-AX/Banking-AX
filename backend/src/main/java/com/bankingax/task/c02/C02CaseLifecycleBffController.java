package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff "검토 시작" and customer cancellation. Both are conditional updates on the same Case row,
 * so when they race exactly one succeeds (spec 3-2, T-02/T-03). Requires DB review persistence.
 */
@RestController
@com.bankingax.core.persistence.ConditionalOnDataSource
public class C02CaseLifecycleBffController {
    private static final String TASK = "INHERITANCE_DEPOSIT";
    private final ObjectProvider<C02StaffDecisionPersistence> persistence;
    private final C02ReviewerAccess reviewers;
    private final TaskBffPrincipal principals;

    public C02CaseLifecycleBffController(ObjectProvider<C02StaffDecisionPersistence> persistence, TaskBffPrincipal principals, C02ReviewerAccess reviewers) {
        this.reviewers = reviewers;
        this.persistence = persistence;
        this.principals = principals;
    }

    @PostMapping("/api/v1/staff/c02/{caseId}/start-review")
    public ApiEnvelope<C02StaffDecisionPersistence.Lifecycle> startReview(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestBody StartReview body) {
        long staffId = principals.staff(authentication);
        reviewers.require(staffId);
        var result = store().startReview(staffId, caseId, body.revision(), requireRequestId(body.requestId()));
        return ApiEnvelope.success(body.requestId(), TASK, null, caseId, result.revision(), result);
    }

    @PostMapping("/api/v1/customer/c02/{caseId}/cancel")
    public ApiEnvelope<C02StaffDecisionPersistence.Lifecycle> cancel(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestBody Cancel body) {
        long customerId = principals.customer(authentication);
        var result = store().cancelByCustomer(customerId, caseId, requireRequestId(body.requestId()));
        return ApiEnvelope.success(body.requestId(), TASK, null, caseId, result.revision(), result);
    }

    private C02StaffDecisionPersistence store() {
        C02StaffDecisionPersistence store = persistence.getIfAvailable();
        if (store == null) throw new DomainException("FEATURE_UNAVAILABLE", "C-02 review persistence is not enabled.", true);
        return store;
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) throw new DomainException("INVALID_REQUEST", "A requestId of at most 90 characters is required.");
        return requestId;
    }

    public record StartReview(int revision, String requestId) { }
    public record Cancel(String requestId) { }
}
