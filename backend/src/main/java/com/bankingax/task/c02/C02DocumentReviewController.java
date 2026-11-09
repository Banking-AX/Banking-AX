package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** STAFF-06 staff document checks. Reading needs the review grant; writing also needs to be the Case's reviewer. */
@RestController
@RequestMapping("/api/v1/staff/c02/{caseId}")
@ConditionalOnDataSource
public class C02DocumentReviewController {
    private final C02DocumentReviewService reviews;
    private final TaskBffPrincipal principals;
    private final C02ReviewerAccess access;

    public C02DocumentReviewController(C02DocumentReviewService reviews, TaskBffPrincipal principals, C02ReviewerAccess access) {
        this.reviews = reviews;
        this.principals = principals;
        this.access = access;
    }

    private long staff(JwtAuthenticationToken authentication) {
        long staffId = principals.staff(authentication);
        access.require(staffId);
        return staffId;
    }

    @GetMapping("/document-reviews")
    public ApiEnvelope<C02DocumentReviewService.Overview> overview(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        staff(authentication);
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, reviews.overview(caseId));
    }

    @GetMapping("/document-reviews/history")
    public ApiEnvelope<List<C02DocumentReviewService.HistoryEntry>> history(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        staff(authentication);
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, reviews.history(caseId));
    }

    @GetMapping("/review-timeline")
    public ApiEnvelope<List<C02DocumentReviewService.TimelineEntry>> timeline(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        staff(authentication);
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, reviews.timeline(caseId));
    }

    @PutMapping("/documents/{documentId}/review")
    public ApiEnvelope<C02DocumentReviewService.Row> review(JwtAuthenticationToken authentication, @PathVariable long caseId, @PathVariable long documentId,
                                                           @RequestBody C02DocumentReviewService.Command body) {
        long staffId = staff(authentication);
        return ApiEnvelope.success(body.requestId(), "INHERITANCE_DEPOSIT", null, caseId, null, reviews.review(staffId, caseId, documentId, body));
    }
}
