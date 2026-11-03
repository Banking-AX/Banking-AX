package com.bankingax.task.history;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JWT-protected customer endpoint for the T2 task re-entry list. */
@RestController
@RequestMapping("/api/v1/customer/task-history")
public class CustomerTaskHistoryBffController {
    private final CustomerTaskHistoryService history;
    private final TaskBffPrincipal principals;

    public CustomerTaskHistoryBffController(CustomerTaskHistoryService history, TaskBffPrincipal principals) {
        this.history = history;
        this.principals = principals;
    }

    @GetMapping
    public ApiEnvelope<?> list(JwtAuthenticationToken authentication) {
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(UUID.randomUUID().toString(), "CUSTOMER_TASK_HISTORY",
                null, null, null, history.listForCustomer(customerId));
    }
}
