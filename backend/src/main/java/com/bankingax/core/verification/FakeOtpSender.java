package com.bankingax.core.verification;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deliberately does not log or send an OTP. CI must use this sender. */
@Component
@Profile("!production & !e2e")
public class FakeOtpSender implements OtpSender { @Override public void send(String phone, String code) { } }
