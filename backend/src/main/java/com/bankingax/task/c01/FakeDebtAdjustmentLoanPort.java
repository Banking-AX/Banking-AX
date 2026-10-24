package com.bankingax.task.c01;

import com.bankingax.core.api.DomainException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Development fixture only; production must replace this with a Spring-owned loan read adapter. */
@Service
public class FakeDebtAdjustmentLoanPort implements DebtAdjustmentLoanPort {
    private final Map<Long, C01LoanProfile> loans = new HashMap<>();
    public FakeDebtAdjustmentLoanPort() {
        loans.put(101L, new C01LoanProfile(101, 1, true, 12_000_000L, null, false, false, false, false, false));
        loans.put(102L, new C01LoanProfile(102, 2, false, 8_000_000L, null, false, false, false, false, false));
    }
    @Override public C01LoanProfile findOwned(long customerId, long loanId) {
        C01LoanProfile loan = loans.get(loanId);
        if (loan == null) throw new DomainException("NOT_FOUND", "Loan was not found.");
        if (loan.customerId() != customerId) throw new DomainException("FORBIDDEN", "Loan ownership does not match.");
        return loan;
    }
}
