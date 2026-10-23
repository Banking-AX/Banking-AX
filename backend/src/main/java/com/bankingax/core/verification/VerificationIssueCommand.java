package com.bankingax.core.verification;

public record VerificationIssueCommand(long customerId, long workItemId, VerificationAction action,
                                       int requestVersion, String registeredPhone) { }
