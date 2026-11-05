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
        ImageCheck image = checkImage(evaluation.result(), requested);
        VerificationState verification = verification(evaluation.result(), loan.thirdPartyProvider());
        boolean eligible = ruleCode == RuleCode.ELIGIBLE;
        boolean automaticProcessingAllowed = eligible && ocr.matched() && image.matched()
                && verification.signaturesVerified() && verification.authenticityVerified()
                && !verification.manualVerificationRequired();
        return new ValidationResult(workItemId, loan.id(), requested.name(), ruleCode, eligible,
                loan.thirdPartyProvider(), loan.thirdPartyProvider(), ocr, image, verification,
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
        if (result.visualChecks() == null
                && (result.fields() == null || !requested.name().equals(result.fields().requestedRepaymentOrder())))
            mismatches.add(MismatchCode.REQUESTED_ORDER_MISMATCH);
        return new OcrEvaluation(new OcrCheck(mismatches.isEmpty(), List.copyOf(mismatches),
                extraction.documentFileId()), result);
    }

    private static ImageCheck checkImage(B01OcrPort.OcrResult result, RequestedOrder requested) {
        if (result == null || result.visualChecks() == null)
            return new ImageCheck(false, ImageStatus.NOT_AVAILABLE,
                    List.of(ImageMismatchCode.IMAGE_ANALYSIS_NOT_AVAILABLE));
        B01OcrPort.VisualChecks checks = result.visualChecks();
        List<ImageMismatchCode> mismatches = new ArrayList<>();
        if (checks.failureReason() != null) mismatches.add(ImageMismatchCode.IMAGE_ANALYSIS_UNAVAILABLE);
        if (!"ALIGNED".equals(checks.alignmentStatus())) mismatches.add(ImageMismatchCode.ALIGNMENT_FAILED);
        if (checks.uncertain() || "UNCERTAIN".equals(checks.firstBox())
                || "UNCERTAIN".equals(checks.secondBox())
                || "UNCERTAIN".equals(checks.selectedOrder()))
            mismatches.add(ImageMismatchCode.IMAGE_ANALYSIS_UNCERTAIN);
        boolean first = "CHECKED".equals(checks.firstBox());
        boolean second = "CHECKED".equals(checks.secondBox());
        if (first == second) mismatches.add(first ? ImageMismatchCode.MULTIPLE_CHECKBOXES
                : ImageMismatchCode.CHECKBOX_NOT_SELECTED);
        if (!requested.name().equals(checks.selectedOrder()))
            mismatches.add(ImageMismatchCode.REQUESTED_ORDER_MISMATCH);
        if (!"TRACE_PRESENT".equals(checks.debtorSignatureTrace()))
            mismatches.add(ImageMismatchCode.DEBTOR_SIGNATURE_TRACE_NOT_FOUND);
        List<ImageMismatchCode> distinct = mismatches.stream().distinct().toList();
        ImageStatus status = distinct.isEmpty() ? ImageStatus.MATCHED
                : distinct.contains(ImageMismatchCode.IMAGE_ANALYSIS_UNAVAILABLE)
                ? ImageStatus.UNAVAILABLE
                : distinct.contains(ImageMismatchCode.IMAGE_ANALYSIS_UNCERTAIN)
                ? ImageStatus.UNCERTAIN : ImageStatus.REQUIRES_RESUBMISSION;
        return new ImageCheck(distinct.isEmpty(), status, distinct);
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
    public enum ImageStatus { MATCHED, REQUIRES_RESUBMISSION, UNCERTAIN, UNAVAILABLE, NOT_AVAILABLE }
    public enum ImageMismatchCode { IMAGE_ANALYSIS_NOT_AVAILABLE, IMAGE_ANALYSIS_UNAVAILABLE,
        ALIGNMENT_FAILED, IMAGE_ANALYSIS_UNCERTAIN, CHECKBOX_NOT_SELECTED, MULTIPLE_CHECKBOXES,
        REQUESTED_ORDER_MISMATCH, DEBTOR_SIGNATURE_TRACE_NOT_FOUND }

    public record OcrCheck(boolean matched, List<MismatchCode> mismatchCodes, Long documentFileId) { }
    public record ImageCheck(boolean matched, ImageStatus status,
                             List<ImageMismatchCode> mismatchCodes) { }
    public record VerificationState(boolean signaturesVerified, boolean authenticityVerified,
                                    boolean manualVerificationRequired) { }
    public record ValidationResult(long workItemId, long loanId, String requestedRepaymentOrder,
                                   RuleCode ruleCode, boolean loanRuleEligible,
                                   boolean thirdPartyProvider, boolean additionalConfirmationRequired,
                                   OcrCheck ocr, ImageCheck image, VerificationState verification,
                                   boolean automaticProcessingAllowed,
                                   String householdLoanClassification) { }
    private record OcrEvaluation(OcrCheck check, B01OcrPort.OcrResult result) { }
}
