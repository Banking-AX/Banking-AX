package com.bankingax.core.chat;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/chat/rooms")
@ConditionalOnBean(WorkflowStartService.class)
public class ChatTaskActionController {
    private final WorkflowStartService starts;
    private final TaskBffPrincipal principals;

    public ChatTaskActionController(WorkflowStartService starts, TaskBffPrincipal principals) {
        this.starts = starts;
        this.principals = principals;
    }

    @PostMapping("/{roomId}/task-actions")
    public ApiEnvelope<WorkflowStartService.StartResult> start(
            JwtAuthenticationToken authentication,
            @PathVariable long roomId,
            @RequestBody TaskActionRequest request) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role"))) {
            throw new DomainException("FORBIDDEN", "Customer access is required.");
        }
        if (request == null || request.actionRequestId() == null || request.actionRequestId().isBlank()
                || request.actionRequestId().length() > 90) {
            throw new DomainException("INVALID_REQUEST", "A valid actionRequestId is required.");
        }
        TaskCode taskCode = parseSupportedTask(request.taskCode());
        long customerId = principals.customer(authentication);
        WorkflowStartService.StartResult result = starts.startOrReconnect(
                new WorkflowStartService.StartCommand(
                        request.actionRequestId(), customerId, roomId, taskCode));
        return ApiEnvelope.success(
                request.actionRequestId(), taskCode.name(), result.workItemId(), null, null, result);
    }

    private static TaskCode parseSupportedTask(String value) {
        if (!TaskCode.REPAYMENT_ORDER_CHANGE.name().equals(value)) {
            throw new DomainException("INVALID_TASK_CODE", "This chat task action is not supported.");
        }
        return TaskCode.REPAYMENT_ORDER_CHANGE;
    }

    public record TaskActionRequest(String actionRequestId, String taskCode) { }
}
