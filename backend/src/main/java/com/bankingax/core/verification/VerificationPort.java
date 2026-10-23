package com.bankingax.core.verification;

public interface VerificationPort {
    VerificationIssue issue(VerificationIssueCommand command);
    VerificationBinding verify(VerificationVerifyCommand command);
    void consume(VerificationConsumeCommand command);
}
