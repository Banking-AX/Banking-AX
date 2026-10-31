package com.bankingax.core.auth;

final class RefreshTokenFailureException extends RuntimeException {
    RefreshTokenFailureException() {
        super("Refresh token is missing, expired, revoked, or invalid.");
    }
}
