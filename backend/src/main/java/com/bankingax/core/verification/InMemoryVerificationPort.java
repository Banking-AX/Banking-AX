package com.bankingax.core.verification;

import com.bankingax.core.api.DomainException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class InMemoryVerificationPort implements VerificationPort {
    private final OtpSender sender; private final Cache<String, Challenge> challenges; private final Cache<String, VerificationBinding> bindings;
    private final int maxAttempts; private final RegisteredPhoneResolver phones;
    public InMemoryVerificationPort(OtpSender sender, long ttl, int maxAttempts) { this(sender, ttl, maxAttempts, (RegisteredPhoneResolver) null); }
    @Autowired
    public InMemoryVerificationPort(OtpSender sender, @Value("${app.sms.otp-ttl-seconds:180}") long ttl,
                                   @Value("${app.sms.max-attempts:5}") int maxAttempts, ObjectProvider<RegisteredPhoneResolver> phones) {
        this(sender, ttl, maxAttempts, phones.getIfAvailable());
    }
    InMemoryVerificationPort(OtpSender sender, long ttl, int maxAttempts, RegisteredPhoneResolver phones) {
        this.sender=sender; this.maxAttempts=maxAttempts; this.phones=phones;
        this.challenges=Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(ttl)).build();
        this.bindings=Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(ttl)).build();
    }
    @Override public VerificationIssue issue(VerificationIssueCommand command) {
        // With a database the recipient is always the registered user.phone, never a phone sent by the caller.
        String phone=phones==null ? command.registeredPhone() : phones.phone(command.customerId())
            .orElseThrow(() -> new DomainException("REGISTERED_PHONE_NOT_AVAILABLE","A registered phone number is required."));
        String id=UUID.randomUUID().toString(); String otp="%06d".formatted(new SecureRandom().nextInt(1_000_000)); Instant expires=Instant.now().plusSeconds(180);
        challenges.put(id,new Challenge(command,otp,0,expires));
        try { sender.send(phone,otp); }
        catch (RuntimeException exception) {
            // T-20: a failed send leaves no usable challenge, so the task cannot proceed on this request.
            challenges.invalidate(id);
            throw new DomainException("SMS_SEND_FAILED","The verification SMS could not be sent.",true);
        }
        return new VerificationIssue(id, mask(phone),expires);
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
