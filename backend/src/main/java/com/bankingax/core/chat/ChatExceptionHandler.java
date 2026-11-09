package com.bankingax.core.chat;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.ApiError;
import com.bankingax.core.api.DomainException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
    ChatController.class, ChatAgentController.class, ChatTaskActionController.class,
    B01ReconnectController.class
})
class ChatExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiEnvelope<Void>> handleDomain(DomainException exception) {
        HttpStatus status = switch (exception.code()) {
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "CHAT_ROOM_NOT_AVAILABLE", "CUSTOMER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE_REQUEST_CONFLICT", "CHAT_MESSAGE_CONFLICT", "VERSION_CONFLICT",
                    "WORKFLOW_ACTIVE_EXISTS", "ACTIVE_WORK_ITEM_CONFLICT", "INVALID_STATE" -> HttpStatus.CONFLICT;
            case "CUSTOMER_AGENT_INVALID_RESPONSE" -> HttpStatus.BAD_GATEWAY;
            case "CHAT_PERSISTENCE_UNAVAILABLE", "CUSTOMER_AGENT_UNAVAILABLE" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case "CUSTOMER_AGENT_AUTH_FAILED", "CUSTOMER_AGENT_CONFIGURATION_INVALID" ->
                    HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        ApiError error = new ApiError(
                exception.code(),
                exception.getMessage(),
                exception.retryable(),
                UUID.randomUUID().toString());
        return ResponseEntity.status(status)
                .body(new ApiEnvelope<>(null, null, null, null, null, null, error));
    }
}
