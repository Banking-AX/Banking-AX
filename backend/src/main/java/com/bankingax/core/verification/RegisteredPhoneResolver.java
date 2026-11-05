package com.bankingax.core.verification;

import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The OTP recipient is the customer's registered phone in user.phone (spec 01-1: Spring owns SMS issuance).
 * A phone number supplied by a browser or task request is never used as the recipient when this bean exists.
 */
@Component
@ConditionalOnDataSource
public class RegisteredPhoneResolver {
    private final JdbcTemplate jdbc;

    public RegisteredPhoneResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> phone(long customerId) {
        return jdbc.query("select phone from `user` where id=? and is_active=true",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)).filter(value -> !value.isBlank()) : Optional.<String>empty(), customerId);
    }
}
