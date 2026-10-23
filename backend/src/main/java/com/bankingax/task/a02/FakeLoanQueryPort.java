package com.bankingax.task.a02;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Contract fixture only; replace with a JPA adapter for the loan table in A-02. */
@Component
@Profile("!production")
public class FakeLoanQueryPort implements LoanQueryPort {
    private static final LoanSummary DEMO_LOAN = new LoanSummary(101L, "210-***-557731", "AX 개인 신용대출",
            new BigDecimal("17200000.00"), new BigDecimal("7.2000"), 45, true, "INTEREST_FIRST");
    @Override public List<LoanSummary> findOwnedLoans(long customerId, Long loanId) {
        if (customerId != 1L || (loanId != null && loanId != 101L)) return List.of();
        return List.of(DEMO_LOAN);
    }
}
