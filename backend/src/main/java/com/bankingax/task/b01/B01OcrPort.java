package com.bankingax.task.b01;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

interface B01OcrPort {
    OcrResult extract(ExtractRequest request);

    record ExtractRequest(long documentFileId, String documentTypeCode, String checksum) { }

    record OcrResult(String documentTypeCode, String detectedType, String method, String status,
                     Fields fields, SignatureChecks signatureChecks, boolean authenticityVerified,
                     boolean manualVerificationRequired, String failureReason) { }

    record Fields(List<LoanField> loans, String requestedRepaymentOrder) { }

    record LoanField(String accountNumber, BigDecimal balance, LocalDate accelerationDate) { }

    record SignatureChecks(String debtorSignatureField, String thirdPartySignatureField) { }
}
