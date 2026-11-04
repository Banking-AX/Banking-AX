package com.bankingax.core.workflow;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer BFF boundary for common WorkItem state transitions. */
@RestController
@RequestMapping("/api/v1/customer/work-items")
public class CustomerWorkItemController {
    private final TaskHandler tasks;
    private final TaskBffPrincipal principals;

    public CustomerWorkItemController(TaskHandler tasks, TaskBffPrincipal principals) {
        this.tasks = tasks;
        this.principals = principals;
    }

    @PostMapping("/{workItemId}/resume")
    public ApiEnvelope<WorkItemView> resume(JwtAuthenticationToken authentication,
                                             @PathVariable long workItemId,
                                             @RequestBody ResumeRequest request) {
        requireCustomer(authentication);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new DomainException("INVALID_REQUEST", "A non-negative expectedVersion is required.");
        }
        try {
            WorkItemView resumed = tasks.resume(
                    principals.customer(authentication), workItemId, request.expectedVersion());
            return ApiEnvelope.success(null, resumed.taskCode().name(), resumed.workItemId(),
                    null, null, resumed);
        } catch (DomainException exception) {
            if ("FORBIDDEN".equals(exception.code()) || "NOT_FOUND".equals(exception.code())) {
                throw unavailable();
            }
            throw exception;
        }
    }

    private static void requireCustomer(JwtAuthenticationToken authentication) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role"))) {
            throw new DomainException("FORBIDDEN", "Only an authenticated customer can resume work.");
        }
    }

    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The work item is not available.");
    }

    public record ResumeRequest(Integer expectedVersion) { }
}
