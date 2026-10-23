package com.bankingax.core.verification;

import com.bankingax.core.api.DomainException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class InMemoryVerificationPort implements VerificationPort {
    private final OtpSender sender; private final Cache<String, Challenge> challenges; private final Cache<String, VerificationBinding> bindings;
    private final int maxAttempts;
    public InMemoryVerificationPort(OtpSender sender, @Value("${app.sms.otp-ttl-seconds:180}") long ttl,
                                   @Value("${app.sms.max-attempts:5}") int maxAttempts) {
        this.sender=sender; this.maxAttempts=maxAttempts;
        this.challenges=Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(ttl)).build();
        this.bindings=Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(ttl)).build();
    }
    @Override public VerificationIssue issue(VerificationIssueCommand command) {
        String id=UUID.randomUUID().toString(); String otp="%06d".formatted(new SecureRandom().nextInt(1_000_000)); Instant expires=Instant.now().plusSeconds(180);
        challenges.put(id,new Challenge(command,otp,0,expires)); sender.send(command.registeredPhone(),otp);
        return new VerificationIssue(id, mask(command.registeredPhone()),expires);
    }
    @Override public VerificationBinding verify(VerificationVerifyCommand command) {
        Challenge challenge=challenges.getIfPresent(command.challengeId());
        if(challenge==null || challenge.command.customerId()!=command.customerId()) throw new DomainException("OTP_INVALID","OTP challenge is invalid.");
        if(challenge.attempts>=maxAttempts) throw new DomainException("OTP_ATTEMPTS_EXCEEDED","OTP attempts exceeded.");
        challenge.attempts++; if(!challenge.otp.equals(command.otp())) throw new DomainException("OTP_INVALID","OTP is invalid.");
        challenges.invalidate(command.challengeId()); VerificationIssueCommand c=challenge.command;
        VerificationBinding binding=new VerificationBinding(UUID.randomUUID().toString(),c.customerId(),c.workItemId(),c.action(),c.requestVersion(),challenge.expiresAt);
        bindings.put(binding.bindingId(),binding); return binding;
    }
    @Override public void consume(VerificationConsumeCommand command) {
        VerificationBinding b=bindings.getIfPresent(command.bindingId());
        if(b==null || b.customerId()!=command.customerId() || b.workItemId()!=command.workItemId() || b.action()!=command.action() || b.requestVersion()!=command.requestVersion())
            throw new DomainException("VERIFICATION_BINDING_INVALID","Verification binding cannot be used for this request.");
        bindings.invalidate(command.bindingId());
    }
    private String mask(String phone) { return phone == null || phone.length()<4 ? "***" : phone.substring(0,3)+"-****-"+phone.substring(phone.length()-4); }
    private static final class Challenge { final VerificationIssueCommand command; final String otp; int attempts; final Instant expiresAt; Challenge(VerificationIssueCommand command,String otp,int attempts,Instant expiresAt){this.command=command;this.otp=otp;this.attempts=attempts;this.expiresAt=expiresAt;} }
}
