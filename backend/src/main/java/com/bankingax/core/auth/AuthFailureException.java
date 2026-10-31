package com.bankingax.core.auth;

final class AuthFailureException extends RuntimeException {
    AuthFailureException() {
        super("Authentication failed.");
    }
}
