package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff JWT boundary for C-02 precheck viewing and human final decisions. */
@RestController
@RequestMapping("/api/v1/staff/c02")
public class C02StaffBffController {
    private final C02Controller internal;
    private final TaskBffPrincipal principals;
    private final String gatewayToken;

    public C02StaffBffController(C02Controller internal, TaskBffPrincipal principals,
                                 @Value("${app.tool-gateway-token:}") String gatewayToken) {
        this.internal = internal;
        this.principals = principals;
        this.gatewayToken = gatewayToken;
    }

    @GetMapping("/{caseId}/staff-case")
    public ApiEnvelope<C02IntakeService.StaffCaseOverview> staffCase(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestParam String requestId) {
        principals.staff(authentication);
        return internal.staffCase(caseId, ActorRole.STAFF, gatewayToken, requestId);
    }

    @GetMapping("/{caseId}/ocr")
    public ApiEnvelope<OcrPersistence.Snapshot> ocr(JwtAuthenticationToken authentication, @PathVariable long caseId,
            @RequestParam int revision, @RequestParam String requestId) {
        principals.staff(authentication);
        return internal.staffOcr(caseId, ActorRole.STAFF, gatewayToken, revision, requestId);
    }

    @GetMapping("/{caseId}/staff-review")
    public ApiEnvelope<C02IntakeService.StaffPrecheckResult> staffReview(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestParam String requestId) {
        principals.staff(authentication);
        return internal.staffReview(caseId, ActorRole.STAFF, gatewayToken, requestId);
    }

    /** Staff must read the original file themselves; Agent/OCR output never replaces it. */
    @GetMapping("/{caseId}/documents/{documentId}/content")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> document(
            JwtAuthenticationToken authentication, @PathVariable long caseId, @PathVariable long documentId) {
        long staffId = principals.staff(authentication);
        return internal.download(caseId, documentId, staffId, ActorRole.STAFF, gatewayToken, null);
    }

    @PostMapping("/{caseId}/staff-precheck")
    public ApiEnvelope<C02IntakeService.StaffPrecheckResult> precheck(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestBody Revision body) {
        principals.staff(authentication);
        return internal.precheck(caseId, 0L, ActorRole.STAFF, gatewayToken,
                new C02Controller.StaffPrecheckBody(body.revision(), body.requestId()));
    }

    @PostMapping("/{caseId}/staff-decision")
    public ApiEnvelope<C02IntakeService.StaffDecisionResult> decision(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestBody Decision body) {
        long staffId = principals.staff(authentication);
        return internal.staffDecision(caseId, staffId, ActorRole.STAFF, gatewayToken,
                new C02Controller.StaffDecisionBody(body.revision(), body.requestId(), body.outcome(), body.customerMessage()));
    }

    public record Revision(int revision, String requestId) { }
    public record Decision(int revision, String requestId, C02IntakeService.StaffOutcome outcome,
                           String customerMessage) { }
}
