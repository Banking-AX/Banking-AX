package com.bankingax.task.b01;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface B01ValidationQueryPort {
    Optional<String> ownedWorkItemStatus(long customerId, long workItemId);
    Optional<LoanRecord> ownedLoan(long customerId, long loanId);
    List<LoanListRecord> ownedLoans(long customerId);
    Optional<ExtractionRecord> latestExtraction(long workItemId);
    Optional<ExtractionRecord> extractionForDocument(long workItemId, long documentFileId);

    record LoanRecord(long id, String loanNo, BigDecimal balance, boolean accelerationDeclared,
                      LocalDate accelerationDate, boolean outsourced, boolean agreementLoan,
                      String repaymentOrder, boolean thirdPartyProvider) { }
    record LoanListRecord(long id, String loanNo, String loanName, BigDecimal balance, int overdueDays,
                          boolean accelerationDeclared, LocalDate accelerationDate,
                          String repaymentOrder) { }
    record ExtractionRecord(long documentFileId, String status, String extracted) { }
}
