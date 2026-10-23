package com.bankingax.core.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiEnvelope<Void>> handleDomain(DomainException ex) {
        HttpStatus status = switch (ex.code()) {
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        String traceId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(new ApiEnvelope<>(null, null, null, null, null,
                null, new ApiError(ex.code(), ex.getMessage(), ex.retryable(), traceId)));
    }
}
