package com.bankingax.core.workflow.jpa;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.ApiError;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.workflow.TaskCode;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
                new WorkflowStartService.StartCommand(
                        request.requestId(), request.customerId(), request.chatRoomId(), taskCode));
        return ApiEnvelope.success(request.requestId(), taskCode.name(), result.workItemId(), null, null, result);
    }

    @PostMapping("/b01/prepare")
    public ApiEnvelope<WorkflowStartService.B01PrepareResult> prepareB01(
            @RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader("X-Tool-Gateway-Token") String gatewayToken,
            @RequestBody B01PrepareRequest request) {
        authenticator.verify(gatewayToken);
        if (role != ActorRole.CUSTOMER || actorId != request.customerId()) {
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        }
        WorkflowStartService.B01PrepareMode mode = parseB01Mode(request.mode());
        WorkflowStartService.B01PrepareResult result = starts.prepareB01(
                new WorkflowStartService.B01PrepareCommand(
                        request.requestId(), request.customerId(), request.chatRoomId(), mode, request.workItemId()));
        return ApiEnvelope.success(
                request.requestId(), TaskCode.REPAYMENT_ORDER_CHANGE.name(),
                result.workItem().workItemId(), null, null, result);
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiEnvelope<Void>> handleDomain(DomainException exception) {
        HttpStatus status = switch (exception.code()) {
            case "DUPLICATE_REQUEST_CONFLICT" -> HttpStatus.CONFLICT;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        ApiError error = new ApiError(
                exception.code(), exception.getMessage(), exception.retryable(), UUID.randomUUID().toString());
        return ResponseEntity.status(status)
                .body(new ApiEnvelope<>(null, null, null, null, null, null, error));
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

    private static WorkflowStartService.B01PrepareMode parseB01Mode(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException("INVALID_REQUEST", "A supported B-01 mode is required.");
        }
        try {
            return WorkflowStartService.B01PrepareMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new DomainException("INVALID_REQUEST", "A supported B-01 mode is required.");
        }
    }

    public record StartRequest(String requestId, long customerId, Long chatRoomId, String taskCode) { }

    public record B01PrepareRequest(String requestId, long customerId, long chatRoomId,
                                    String mode, Long workItemId) { }
}
