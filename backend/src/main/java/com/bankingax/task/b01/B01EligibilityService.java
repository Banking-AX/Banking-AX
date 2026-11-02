package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class B01EligibilityService {
    private final B01ValidationQueryPort queries;
    private final ObjectMapper json;

    B01EligibilityService(ObjectProvider<B01ValidationQueryPort> queries, ObjectMapper json) {
        this.queries = queries.getIfAvailable();
        this.json = json;
    }

    public ValidationResult validate(long customerId, long workItemId, long loanId,
                                     String requestedRepaymentOrder) {
        requirePort();
        RequestedOrder requested = requestedOrder(requestedRepaymentOrder);
        String workItemStatus = queries.ownedWorkItemStatus(customerId, workItemId)
                .orElseThrow(B01EligibilityService::unavailable);
        if (!"ACTIVE".equals(workItemStatus))
            throw new DomainException("WORK_ITEM_NOT_VALIDATABLE", "The B-01 work item is not active.");

        B01ValidationQueryPort.LoanRecord loan = queries.ownedLoan(customerId, loanId)
                .orElseThrow(() -> new DomainException("LOAN_NOT_AVAILABLE", "The selected loan is not available."));
        RuleCode ruleCode = rule(loan, requested);
        OcrEvaluation evaluation = checkOcr(queries.latestExtraction(workItemId).orElse(null), loan, requested);
        OcrCheck ocr = evaluation.check();
        VerificationState verification = verification(evaluation.result(), loan.thirdPartyProvider());
        boolean eligible = ruleCode == RuleCode.ELIGIBLE;
        boolean automaticProcessingAllowed = eligible && ocr.matched()
                && verification.signaturesVerified() && verification.authenticityVerified()
                && !verification.manualVerificationRequired();
        return new ValidationResult(workItemId, loan.id(), requested.name(), ruleCode, eligible,
                loan.thirdPartyProvider(), loan.thirdPartyProvider(), ocr, verification,
                automaticProcessingAllowed, "NOT_AVAILABLE_IN_SCHEMA");
    }

    private OcrEvaluation checkOcr(B01ValidationQueryPort.ExtractionRecord extraction,
                                   B01ValidationQueryPort.LoanRecord loan, RequestedOrder requested) {
        if (extraction == null)
            return new OcrEvaluation(new OcrCheck(false, List.of(MismatchCode.OCR_NOT_AVAILABLE), null), null);
        if (!"SUCCEEDED".equals(extraction.status()))
            return new OcrEvaluation(new OcrCheck(false, List.of(MismatchCode.OCR_NOT_SUCCEEDED),
                    extraction.documentFileId()), null);
        B01OcrPort.OcrResult result;
        try {
            result = json.readValue(extraction.extracted(), B01OcrPort.OcrResult.class);
        } catch (Exception exception) {
            return new OcrEvaluation(new OcrCheck(false, List.of(MismatchCode.OCR_RESULT_INVALID),
                    extraction.documentFileId()), null);
        }
        List<MismatchCode> mismatches = new ArrayList<>();
        if (result.fields() == null || result.fields().loans() == null || result.fields().loans().size() != 1) {
            mismatches.add(MismatchCode.OCR_LOAN_COUNT_INVALID);
        } else {
            B01OcrPort.LoanField field = result.fields().loans().get(0);
            if (!loan.loanNo().equals(field.accountNumber())) mismatches.add(MismatchCode.ACCOUNT_NUMBER_MISMATCH);
            if (field.balance() == null || loan.balance().compareTo(field.balance()) != 0)
                mismatches.add(MismatchCode.BALANCE_MISMATCH);
            if (!java.util.Objects.equals(loan.accelerationDate(), field.accelerationDate()))
                mismatches.add(MismatchCode.ACCELERATION_DATE_MISMATCH);
        }
        if (result.fields() == null || !requested.name().equals(result.fields().requestedRepaymentOrder()))
            mismatches.add(MismatchCode.REQUESTED_ORDER_MISMATCH);
        return new OcrEvaluation(new OcrCheck(mismatches.isEmpty(), List.copyOf(mismatches),
                extraction.documentFileId()), result);
    }

    private static VerificationState verification(B01OcrPort.OcrResult result, boolean thirdPartyProvider) {
        if (result == null) return new VerificationState(false, false, true);
        boolean signatures = result.signatureChecks() != null
                && "VERIFIED".equals(result.signatureChecks().debtorSignatureField())
                && (!thirdPartyProvider
                    || "VERIFIED".equals(result.signatureChecks().thirdPartySignatureField()));
        return new VerificationState(signatures, result.authenticityVerified(),
                result.manualVerificationRequired() || !signatures || !result.authenticityVerified());
    }

    private static RuleCode rule(B01ValidationQueryPort.LoanRecord loan, RequestedOrder requested) {
        if (!loan.accelerationDeclared()) return RuleCode.NOT_ACCELERATED;
        if (loan.outsourced()) return RuleCode.OUTSOURCED_LOAN;
        if (loan.agreementLoan()) return RuleCode.AGREEMENT_LOAN;
        if (requested.name().equals(loan.repaymentOrder())) return RuleCode.ALREADY_APPLIED;
        return RuleCode.ELIGIBLE;
    }

    private static RequestedOrder requestedOrder(String value) {
        try { return RequestedOrder.valueOf(value); }
        catch (RuntimeException exception) {
            throw new DomainException("INVALID_REPAYMENT_ORDER",
                    "Requested repayment order must be INTEREST_FIRST or PRINCIPAL_FIRST.");
        }
    }

    private void requirePort() {
        if (queries == null) throw new DomainException("B01_PERSISTENCE_UNAVAILABLE",
                "B-01 validation persistence is not configured.", true);
    }

    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
    }

    enum RequestedOrder { INTEREST_FIRST, PRINCIPAL_FIRST }
    public enum RuleCode { NOT_ACCELERATED, OUTSOURCED_LOAN, AGREEMENT_LOAN, ALREADY_APPLIED, ELIGIBLE }
    public enum MismatchCode { OCR_NOT_AVAILABLE, OCR_NOT_SUCCEEDED, OCR_RESULT_INVALID,
        OCR_LOAN_COUNT_INVALID, ACCOUNT_NUMBER_MISMATCH, BALANCE_MISMATCH,
        ACCELERATION_DATE_MISMATCH, REQUESTED_ORDER_MISMATCH }

    public record OcrCheck(boolean matched, List<MismatchCode> mismatchCodes, Long documentFileId) { }
    public record VerificationState(boolean signaturesVerified, boolean authenticityVerified,
                                    boolean manualVerificationRequired) { }
    public record ValidationResult(long workItemId, long loanId, String requestedRepaymentOrder,
                                   RuleCode ruleCode, boolean loanRuleEligible,
                                   boolean thirdPartyProvider, boolean additionalConfirmationRequired,
                                   OcrCheck ocr, VerificationState verification,
                                   boolean automaticProcessingAllowed,
                                   String householdLoanClassification) { }
    private record OcrEvaluation(OcrCheck check, B01OcrPort.OcrResult result) { }
}
