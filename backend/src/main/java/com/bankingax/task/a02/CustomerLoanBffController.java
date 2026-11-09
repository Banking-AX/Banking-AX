package com.bankingax.task.a02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JWT customer boundary for the read-only A-02 loan detail page. */
@RestController
@RequestMapping("/api/v1/customer/loans")
@ConditionalOnDataSource
public class CustomerLoanBffController {
    private static final String TASK_CODE = "LOAN_INQUIRY";

    private final CustomerLoanQueryService loans;
    private final TaskBffPrincipal principals;

    public CustomerLoanBffController(CustomerLoanQueryService loans, TaskBffPrincipal principals) {
        this.loans = loans;
        this.principals = principals;
    }

    @GetMapping
    public ApiEnvelope<CustomerLoanQueryService.CustomerLoanList> list(
            JwtAuthenticationToken authentication, @RequestParam String requestId) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role"))) {
            throw new DomainException("FORBIDDEN", "Only an authenticated customer can view loans.");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) {
            throw new DomainException("INVALID_REQUEST", "A requestId of at most 90 characters is required.");
        }
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(requestId, TASK_CODE, null, null, null,
                loans.findOwnedLoans(customerId));
    }
}
