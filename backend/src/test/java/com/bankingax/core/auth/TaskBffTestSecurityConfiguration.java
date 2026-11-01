package com.bankingax.core.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@TestConfiguration
public class TaskBffTestSecurityConfiguration {
    @Bean
    SecurityFilterChain taskBffTestFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/customer/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers("/api/v1/staff/**").hasAnyRole("EMPLOYEE", "ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
