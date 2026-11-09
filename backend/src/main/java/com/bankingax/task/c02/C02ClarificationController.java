package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** STAFF-21 / NOTI-20·21. Staff ask with the review grant; only the Case owner reads and answers. */
@RestController
@ConditionalOnDataSource
public class C02ClarificationController {
    private final C02ClarificationService clarifications;
    private final TaskBffPrincipal principals;
    private final C02ReviewerAccess access;

    public C02ClarificationController(C02ClarificationService clarifications, TaskBffPrincipal principals, C02ReviewerAccess access) {
        this.clarifications = clarifications;
        this.principals = principals;
        this.access = access;
    }

    @GetMapping("/api/v1/staff/c02/{caseId}/clarifications")
    public ApiEnvelope<List<C02ClarificationService.Clarification>> staffList(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        access.require(principals.staff(authentication));
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, clarifications.listForStaff(caseId));
    }

    @PostMapping("/api/v1/staff/c02/{caseId}/clarifications")
    public ApiEnvelope<C02ClarificationService.Clarification> ask(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestBody C02ClarificationService.Ask body) {
        long staffId = principals.staff(authentication);
        access.require(staffId);
        return ApiEnvelope.success(body.requestId(), "INHERITANCE_DEPOSIT", null, caseId, null, clarifications.ask(staffId, caseId, body));
    }

    @GetMapping("/api/v1/customer/c02/{caseId}/clarifications")
    public ApiEnvelope<List<C02ClarificationService.Clarification>> customerList(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, clarifications.listForCustomer(customerId, caseId));
    }

    @PostMapping("/api/v1/customer/c02/{caseId}/clarifications/{clarificationId}/answer")
    public ApiEnvelope<C02ClarificationService.Clarification> answer(JwtAuthenticationToken authentication, @PathVariable long caseId, @PathVariable long clarificationId,
                                                                    @RequestBody C02ClarificationService.Answer body) {
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(body.requestId(), "INHERITANCE_DEPOSIT", null, caseId, null, clarifications.answer(customerId, caseId, clarificationId, body));
    }
}
