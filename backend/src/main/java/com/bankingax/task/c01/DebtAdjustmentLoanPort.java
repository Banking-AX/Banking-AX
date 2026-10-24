package com.bankingax.task.c01;

public interface DebtAdjustmentLoanPort {
    C01LoanProfile findOwned(long customerId, long loanId);
}
