package com.bankingax.core.auth;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.ApiError;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {
    @ExceptionHandler(AuthFailureException.class)
    ResponseEntity<ApiEnvelope<Void>> authenticationFailed() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS",
                "Login ID or password is invalid.");
    }

    @ExceptionHandler(RefreshTokenFailureException.class)
    ResponseEntity<ApiEnvelope<Void>> refreshFailed() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_INVALID",
                "The refresh session is unavailable. Please sign in again.");
    }

    private static ResponseEntity<ApiEnvelope<Void>> error(HttpStatus status, String code, String message) {
        String traceId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(new ApiEnvelope<>(null, "AUTH", null, null, null,
                null, new ApiError(code, message, false, traceId)));
    }
}
