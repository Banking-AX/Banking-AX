package com.bankingax.task.b01;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!production")
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock", matchIfMissing = true)
final class B01MockSmsSender implements B01SmsSender {
    @Override public void send(String phone, String text) {
        // Intentionally no logging: the message contains an OTP. Tests use a dedicated sender bean.
    }
}
