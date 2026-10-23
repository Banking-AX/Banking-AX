package com.bankingax.core.verification;

public record VerificationVerifyCommand(long customerId, String challengeId, String otp) { }
