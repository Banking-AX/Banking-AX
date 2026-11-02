package com.bankingax.core.verification;

import com.bankingax.core.api.DomainException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Test-only OTP implementation. It is unavailable unless the explicit e2e profile is active. */
@Service
@Primary
@Profile("e2e")
public class E2eVerificationPort implements VerificationPort, OtpSender {
    static final String TEST_OTP = "000000";
    private final Cache<String, VerificationIssueCommand> challenges = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(3)).build();
    private final Cache<String, VerificationBinding> bindings = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(3)).build();

    @Override public VerificationIssue issue(VerificationIssueCommand command) {
        String challengeId = UUID.randomUUID().toString();
        challenges.put(challengeId, command);
        return new VerificationIssue(challengeId, "***", Instant.now().plusSeconds(180));
    }
    @Override public VerificationBinding verify(VerificationVerifyCommand command) {
        if (command == null || command.challengeId() == null || command.challengeId().isBlank())
            throw new DomainException("OTP_INVALID", "OTP challenge is invalid or expired.");
        VerificationIssueCommand issued = challenges.getIfPresent(command.challengeId());
        if (issued == null || issued.customerId() != command.customerId() || !TEST_OTP.equals(command.otp()))
            throw new DomainException("OTP_INVALID", "OTP is invalid.");
        challenges.invalidate(command.challengeId());
        VerificationBinding binding = new VerificationBinding(UUID.randomUUID().toString(), issued.customerId(),
                issued.workItemId(), issued.action(), issued.requestVersion(), Instant.now().plusSeconds(180));
        bindings.put(binding.bindingId(), binding);
        return binding;
    }
    @Override public void consume(VerificationConsumeCommand command) {
        if (command == null || command.bindingId() == null || command.bindingId().isBlank())
            throw new DomainException("VERIFICATION_BINDING_INVALID", "Verification binding cannot be used for this request.");
        VerificationBinding binding = bindings.getIfPresent(command.bindingId());
        if (binding == null || binding.customerId() != command.customerId() || binding.workItemId() != command.workItemId()
                || binding.action() != command.action() || binding.requestVersion() != command.requestVersion())
            throw new DomainException("VERIFICATION_BINDING_INVALID", "Verification binding cannot be used for this request.");
        bindings.invalidate(command.bindingId());
    }
    @Override public void send(String phone, String code) { }
}
