package com.bankingax.core.api;

public class DomainException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public DomainException(String code, String message) { this(code, message, false); }
    public DomainException(String code, String message, boolean retryable) {
        super(message); this.code = code; this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
