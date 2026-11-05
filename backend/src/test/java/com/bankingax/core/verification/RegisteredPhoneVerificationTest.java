package com.bankingax.core.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spec 01-1 / T-20: the OTP goes only to the registered phone, and a failed send leaves nothing usable. */
class RegisteredPhoneVerificationTest {
    private static RegisteredPhoneResolver registered(String phone) {
        return new RegisteredPhoneResolver(null) {
            @Override public Optional<String> phone(long customerId) { return customerId == 1 ? Optional.ofNullable(phone) : Optional.empty(); }
        };
    }

    private static VerificationIssueCommand command(long customerId, String suppliedPhone) {
        return new VerificationIssueCommand(customerId, 6, VerificationAction.C02_SUBMIT, 3, suppliedPhone);
    }

    @Test void sendsToTheRegisteredPhoneEvenWhenTheRequestSuppliesAnother() {
        List<String> recipients = new ArrayList<>();
        var port = new InMemoryVerificationPort((phone, code) -> recipients.add(phone), 180, 5, registered("010-1234-5678"));
        VerificationIssue issue = port.issue(command(1, "010-9999-0000"));
        assertEquals(List.of("010-1234-5678"), recipients);
        assertEquals("010-****-5678", issue.maskedPhone());
    }

    @Test void rejectsACustomerWithoutARegisteredPhone() {
        var port = new InMemoryVerificationPort((phone, code) -> { }, 180, 5, registered(null));
        DomainException error = assertThrows(DomainException.class, () -> port.issue(command(1, "010-9999-0000")));
        assertEquals("REGISTERED_PHONE_NOT_AVAILABLE", error.code());
        assertEquals("REGISTERED_PHONE_NOT_AVAILABLE",
                assertThrows(DomainException.class, () -> port.issue(command(2, null))).code());
    }

    @Test void failedSendInvalidatesTheChallenge() {
        String[] sent = new String[1];
        var port = new InMemoryVerificationPort((phone, code) -> { sent[0] = code; throw new IllegalStateException("provider down"); },
                180, 5, registered("010-1234-5678"));
        DomainException error = assertThrows(DomainException.class, () -> port.issue(command(1, null)));
        assertEquals("SMS_SEND_FAILED", error.code());
        // Even with the generated code, nothing can be verified because no challenge id was returned and it was discarded.
        assertThrows(DomainException.class, () -> port.verify(new VerificationVerifyCommand(1, "unknown", sent[0])));
    }
}
