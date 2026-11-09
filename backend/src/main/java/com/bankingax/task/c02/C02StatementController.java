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

/** HIST-11. The owner writes and reads; staff with the review grant read. */
@RestController
@ConditionalOnDataSource
public class C02StatementController {
    private final C02StatementService statements;
    private final TaskBffPrincipal principals;
    private final C02ReviewerAccess access;

    public C02StatementController(C02StatementService statements, TaskBffPrincipal principals, C02ReviewerAccess access) {
        this.statements = statements;
        this.principals = principals;
        this.access = access;
    }

    @PostMapping("/api/v1/customer/c02/{caseId}/statements")
    public ApiEnvelope<C02StatementService.Statement> write(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestBody C02StatementService.Write body) {
        return ApiEnvelope.success(body.requestId(), "INHERITANCE_DEPOSIT", null, caseId, null, statements.write(principals.customer(authentication), caseId, body));
    }

    @GetMapping("/api/v1/customer/c02/{caseId}/statements")
    public ApiEnvelope<List<C02StatementService.Statement>> customer(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, statements.listForCustomer(principals.customer(authentication), caseId));
    }

    @GetMapping("/api/v1/staff/c02/{caseId}/statements")
    public ApiEnvelope<List<C02StatementService.Statement>> staff(JwtAuthenticationToken authentication, @PathVariable long caseId, @RequestParam String requestId) {
        access.require(principals.staff(authentication));
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, caseId, null, statements.listForStaff(caseId));
    }
}
