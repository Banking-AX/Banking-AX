package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Staff role alone does not open a customer's Case: reading or acting on one takes the same grant as the pre-review inbox. */
@Component
public class C02ReviewerAccess {
    private final ObjectProvider<JdbcTemplate> jdbc;

    public C02ReviewerAccess(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    /** Refuses when the grant cannot be read, so a missing database never opens a Case. */
    public void require(long staffId) {
        JdbcTemplate template = jdbc.getIfAvailable();
        Boolean allowed = template == null ? null : template.query("select can_pre_review from `user` where id=? and is_active=true",
                rs -> rs.next() ? rs.getBoolean(1) : null, staffId);
        if (!Boolean.TRUE.equals(allowed)) throw new DomainException("FORBIDDEN", "Case review is not granted to this staff account.");
    }
}
