package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Customer JWT boundary for C-02. Customer identity is derived, never posted. */
@RestController
@RequestMapping("/api/v1/customer/c02")
public class C02CustomerBffController {
    private final C02Controller internal;
    private final TaskBffPrincipal principals;
    private final String gatewayToken;

    public C02CustomerBffController(C02Controller internal, TaskBffPrincipal principals,
                                    @Value("${app.tool-gateway-token:}") String gatewayToken) {
        this.internal = internal;
        this.principals = principals;
        this.gatewayToken = gatewayToken;
    }

    @PostMapping("/prepare")
    public ApiEnvelope<C02IntakeService.Prepared> prepare(JwtAuthenticationToken authentication,
                                                            @RequestBody Prepare body) {
        long customerId = principals.customer(authentication);
        return internal.prepare(customerId, ActorRole.CUSTOMER, gatewayToken,
                new C02Controller.PrepareBody(customerId, body.workItemId(), body.requestId(), body.registeredPhone(),
                        body.decedent(), body.heirs(), body.documentStatuses()));
    }

    @PostMapping("/{caseId}/consent")
    public ApiEnvelope<C02IntakeService.C02Draft> consent(JwtAuthenticationToken authentication,
                                                           @PathVariable long caseId, @RequestBody Consent body) {
        long customerId = principals.customer(authentication);
        return internal.consent(caseId, customerId, ActorRole.CUSTOMER, gatewayToken,
                new C02Controller.ConsentBody(customerId, body.revision(), body.requestId(), body.consented(), body.mockSignatureCompleted()));
    }

    @PostMapping("/{caseId}/verification/issue")
    public ApiEnvelope<VerificationIssue> issue(JwtAuthenticationToken authentication,
                                                 @PathVariable long caseId, @RequestBody Revision body) {
        long customerId = principals.customer(authentication);
        return internal.issue(caseId, customerId, ActorRole.CUSTOMER, gatewayToken,
                new C02Controller.RevisionBody(customerId, body.revision(), body.requestId()));
    }

    @PostMapping("/verification/verify")
    public ApiEnvelope<VerificationBinding> verify(JwtAuthenticationToken authentication, @RequestBody Verify body) {
        long customerId = principals.customer(authentication);
        return internal.verify(customerId, ActorRole.CUSTOMER, gatewayToken,
                new C02Controller.VerifyBody(customerId, body.requestId(), body.challengeId(), body.otp()));
    }

    @PostMapping("/{caseId}/submit")
    public ApiEnvelope<C02IntakeService.SubmissionResult> submit(JwtAuthenticationToken authentication,
                                                                  @PathVariable long caseId, @RequestBody Submit body) {
        long customerId = principals.customer(authentication);
        return internal.submit(caseId, customerId, ActorRole.CUSTOMER, gatewayToken,
                new C02Controller.SubmitBody(customerId, body.revision(), body.requestId(), body.bindingId()));
    }

    @PostMapping(value = "/{caseId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<C02IntakeService.DocumentUpdate> upload(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestParam int revision, @RequestParam String requestId,
            @RequestParam String documentSlotKey, @RequestPart MultipartFile file) {
        long customerId = principals.customer(authentication);
        return internal.upload(caseId, customerId, ActorRole.CUSTOMER, gatewayToken, customerId, revision,
                requestId, documentSlotKey, file);
    }

    @GetMapping("/{caseId}/documents")
    public ApiEnvelope<List<DocumentPort.DocumentMetadata>> documents(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return internal.documents(caseId, customerId, ActorRole.CUSTOMER, gatewayToken, customerId, requestId);
    }

    @GetMapping("/{caseId}/review")
    public ApiEnvelope<C02IntakeService.StaffPrecheckResult> review(JwtAuthenticationToken authentication,
            @PathVariable long caseId, @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return internal.customerReview(caseId, customerId, ActorRole.CUSTOMER, gatewayToken, customerId, requestId);
    }

    public record Prepare(long workItemId, String requestId, String registeredPhone,
                          C02Controller.DecedentBody decedent, List<C02Controller.HeirBody> heirs,
                          Map<String, C02IntakeService.DocumentStatus> documentStatuses) { }
    public record Consent(int revision, String requestId, boolean consented, boolean mockSignatureCompleted) { }
    public record Revision(int revision, String requestId) { }
    public record Verify(String requestId, String challengeId, String otp) { }
    public record Submit(int revision, String requestId, String bindingId) { }
}
