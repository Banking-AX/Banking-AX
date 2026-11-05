package com.bankingax.core.verification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
/** Deliberately does not log or send an OTP. CI must use this sender; app.sms.provider=solapi replaces it. */
@Component
@Profile("!production")
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock", matchIfMissing = true)
public class FakeOtpSender implements OtpSender { @Override public void send(String phone, String code) { } }
