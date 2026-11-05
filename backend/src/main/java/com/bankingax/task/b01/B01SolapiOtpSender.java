package com.bankingax.task.b01;

import com.bankingax.core.verification.OtpSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends the shared B/C verification OTP (core VerificationPort: B-02, C-01, C-02) through the existing SOLAPI sender.
 * Active only with app.sms.provider=solapi; tests and CI keep FakeOtpSender. The OTP is never logged.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "solapi")
final class B01SolapiOtpSender implements OtpSender {
    private final B01SmsSender sms;

    B01SolapiOtpSender(B01SmsSender sms) { this.sms = sms; }

    @Override public void send(String phone, String code) {
        sms.send(phone, "[Banking-AX] 인증번호는 " + code + "입니다. 3분 내 입력해 주세요.");
    }
}
