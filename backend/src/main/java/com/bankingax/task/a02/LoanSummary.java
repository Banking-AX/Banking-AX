package com.bankingax.task.a02;

import java.math.BigDecimal;

public record LoanSummary(long loanId, String loanNoMasked, String loanName, BigDecimal balance,
                          BigDecimal interestRate, int overdueDays, boolean accelerationDeclared,
                          String repaymentOrder) { }
