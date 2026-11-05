package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.core.verification.FakeOtpSender;
import com.bankingax.core.verification.OtpSender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The shared B/C OTP uses the real provider only when selected; everything else (CI included) keeps the silent fake. */
class B01SolapiOtpSenderTest {
    @Configuration(proxyBeanMethods = false)
    static class RecordingSms {
        final List<String> messages = new ArrayList<>();
        @Bean B01SmsSender sms() {
            return (phone, text) -> { messages.add(phone + "|" + text); return B01SmsSender.SendReceipt.accepted("TEST"); };
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RecordingSms.class, FakeOtpSender.class, B01SolapiOtpSender.class);

    @Test void solapiProviderReplacesTheFakeAndDeliversTheCode() {
        runner.withPropertyValues("app.sms.provider=solapi").run(context -> {
            assertThat(context).hasSingleBean(OtpSender.class).doesNotHaveBean(FakeOtpSender.class);
            context.getBean(OtpSender.class).send("01000000000", "123456");
            assertThat(context.getBean(RecordingSms.class).messages).singleElement().asString()
                    .startsWith("01000000000|").contains("123456");
        });
    }

    @Test void unsetProviderKeepsTheFakeSoCiNeverSends() {
        runner.run(context -> assertThat(context).hasSingleBean(FakeOtpSender.class).doesNotHaveBean(B01SolapiOtpSender.class));
    }
}
