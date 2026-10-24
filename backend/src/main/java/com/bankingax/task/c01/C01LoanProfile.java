package com.bankingax.task.c01;

import java.time.LocalDate;

/** T1-owned projection for the C-01 Java rule tool. It is not a financial ledger entity. */
public record C01LoanProfile(long loanId, long customerId, boolean overdue, long principalAmount,
                             LocalDate debtAdjustmentTerminatedAt, boolean inLitigation,
                             boolean otherProcedure, boolean repeatedSupplement,
                             boolean materialAbilityChangeConfirmed, boolean prescriptionConcern) { }
