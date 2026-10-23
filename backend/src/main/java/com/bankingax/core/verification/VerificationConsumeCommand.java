package com.bankingax.core.verification;

public record VerificationConsumeCommand(String bindingId, long customerId, long workItemId,
                                         VerificationAction action, int requestVersion) { }
