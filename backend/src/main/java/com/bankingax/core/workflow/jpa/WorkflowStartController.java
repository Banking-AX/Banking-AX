package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.workflow.TaskCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools/workflows")
@ConditionalOnProperty(name = "app.workflow-start-enabled", havingValue = "true")
public class WorkflowStartController {
    private final WorkflowStartService starts;
    private final InternalGatewayAuthenticator authenticator;

    public WorkflowStartController(WorkflowStartService starts, InternalGatewayAuthenticator authenticator) {
        this.starts = starts;
        this.authenticator = authenticator;
    }

    @PostMapping("/start")
    public ApiEnvelope<WorkflowStartService.StartResult> start(
            @RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader("X-Tool-Gateway-Token") String gatewayToken,
            @RequestBody StartRequest request) {
        authenticator.verify(gatewayToken);
        if (role != ActorRole.CUSTOMER || actorId != request.customerId()) {
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        }
        TaskCode taskCode = parseTaskCode(request.taskCode());
        WorkflowStartService.StartResult result = starts.start(
                new WorkflowStartService.StartCommand(request.requestId(), request.customerId(), taskCode));
        return ApiEnvelope.success(request.requestId(), taskCode.name(), result.workItemId(), null, null, result);
    }

    private static TaskCode parseTaskCode(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException("INVALID_TASK_CODE", "A supported taskCode is required.");
        }
        try {
            return TaskCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new DomainException("INVALID_TASK_CODE", "A supported taskCode is required.");
        }
    }

    public record StartRequest(String requestId, long customerId, String taskCode) { }
}
