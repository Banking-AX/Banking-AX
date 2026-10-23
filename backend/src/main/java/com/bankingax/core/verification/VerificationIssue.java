package com.bankingax.core.verification;

import java.time.Instant;

public record VerificationIssue(String challengeId, String maskedPhone, Instant expiresAt) { }
