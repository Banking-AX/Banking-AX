package com.bankingax.task.b01;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

interface B01ValidationQueryPort {
    Optional<String> ownedWorkItemStatus(long customerId, long workItemId);
    Optional<LoanRecord> ownedLoan(long customerId, long loanId);
    Optional<ExtractionRecord> latestExtraction(long workItemId);

    record LoanRecord(long id, String loanNo, BigDecimal balance, boolean accelerationDeclared,
                      LocalDate accelerationDate, boolean outsourced, boolean agreementLoan,
                      String repaymentOrder, boolean thirdPartyProvider) { }
    record ExtractionRecord(long documentFileId, String status, String extracted) { }
}
