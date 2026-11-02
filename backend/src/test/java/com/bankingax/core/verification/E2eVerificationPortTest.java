package com.bankingax.core.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import org.junit.jupiter.api.Test;

class E2eVerificationPortTest {
    private final E2eVerificationPort verification = new E2eVerificationPort();

    @Test
    void missingChallengeIsRejectedAsAnOtpError() {
        DomainException error = assertThrows(DomainException.class,
                () -> verification.verify(new VerificationVerifyCommand(901, null, "000000")));

        assertEquals("OTP_INVALID", error.code());
    }

    @Test
    void missingBindingIsRejectedAsABindingError() {
        DomainException error = assertThrows(DomainException.class,
                () -> verification.consume(new VerificationConsumeCommand(
                        null, 901, 2902, VerificationAction.C02_SUBMIT, 5)));

        assertEquals("VERIFICATION_BINDING_INVALID", error.code());
    }
}
