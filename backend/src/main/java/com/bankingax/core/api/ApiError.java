package com.bankingax.core.api;

public record ApiError(String code, String message, boolean retryable, String traceId) { }
