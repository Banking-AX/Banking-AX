package com.bankingax.task.a02;

import java.util.List;

public interface LoanQueryPort { List<LoanSummary> findOwnedLoans(long customerId, Long loanId); }
