package com.bankingax.core.verification;

import java.time.Instant;

public record VerificationBinding(String bindingId, long customerId, long workItemId, VerificationAction action,
                                  int requestVersion, Instant expiresAt) { }
