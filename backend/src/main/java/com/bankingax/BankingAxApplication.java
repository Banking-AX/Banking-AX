package com.bankingax;

import com.bankingax.core.auth.AuthSecurityConfiguration;
import com.bankingax.core.auth.DisabledAuthSecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({AuthSecurityConfiguration.class, DisabledAuthSecurityConfiguration.class})
public class BankingAxApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankingAxApplication.class, args);
    }
}
