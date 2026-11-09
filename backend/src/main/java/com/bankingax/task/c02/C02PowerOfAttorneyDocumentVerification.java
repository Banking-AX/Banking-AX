package com.bankingax.task.c02;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Non-persistent C-02 power-of-attorney precheck boundary.  File names and MIME
 * types are deliberately not template evidence.  The current local store can
 * only prove that a readable PDF was stored, so it returns TYPE_UNVERIFIABLE
 * until a trusted template/layout detector (for example a CLOVA adapter) is
 * supplied.  No result from this class is written to a Case by itself.
 */
public final class C02PowerOfAttorneyDocumentVerification {
    public static final String FORM_ID = "5-101-0337";
    public static final String FORM_VERSION = "2014-11-28";
    private static final Set<String> REQUIRED_STRUCTURE = Set.of("depositor", "account", "agent", "delegator", "delegation-purpose");
    private C02PowerOfAttorneyDocumentVerification() { }

    public enum TypeStatus { TYPE_MATCHED, TYPE_MISMATCH, TYPE_UNVERIFIABLE }
    public enum ReasonCode { TEMPLATE_STRUCTURE_MATCHED, TRUSTED_NON_POWER_OF_ATTORNEY, TEMPLATE_DETECTOR_UNAVAILABLE, FILE_UNREADABLE, OCR_NOT_RUN, OCR_FAILED, TYPE_NOT_MATCHED, TYPE_UNVERIFIABLE }
    public record DocumentRef(long caseId, int revision, long documentFileId, int documentVersion) { }
    /** Evidence must come from a future trusted template/layout detector, never a filename or one keyword. */
    public record TemplateEvidence(boolean trustedDetector, boolean explicitlyNotPowerOfAttorney,
                                   String formId, String formVersion, Set<String> structuralSections) {
        public TemplateEvidence { structuralSections = structuralSections == null ? Set.of() : Set.copyOf(structuralSections); }
        public static TemplateEvidence unavailable() { return new TemplateEvidence(false, false, null, null, Set.of()); }
    }
    public record TypeResult(TypeStatus status, ReasonCode reason, String formId, String formVersion, boolean manualVerificationRequired) { }
    public record Result(DocumentRef document, TypeResult type, C02PowerOfAttorneyOcrComparison.Comparison comparison,
                         ReasonCode extractionReason, boolean manualVerificationRequired) { }

    public static TypeResult classify(TemplateEvidence evidence) {
        if (evidence == null || !evidence.trustedDetector()) return new TypeResult(TypeStatus.TYPE_UNVERIFIABLE, ReasonCode.TEMPLATE_DETECTOR_UNAVAILABLE, null, null, true);
        if (evidence.explicitlyNotPowerOfAttorney()) return new TypeResult(TypeStatus.TYPE_MISMATCH, ReasonCode.TRUSTED_NON_POWER_OF_ATTORNEY, null, null, true);
        if (FORM_ID.equals(evidence.formId()) && REQUIRED_STRUCTURE.stream().allMatch(evidence.structuralSections()::contains))
            return new TypeResult(TypeStatus.TYPE_MATCHED, ReasonCode.TEMPLATE_STRUCTURE_MATCHED, FORM_ID, evidence.formVersion(), true);
        return new TypeResult(TypeStatus.TYPE_UNVERIFIABLE, ReasonCode.TEMPLATE_DETECTOR_UNAVAILABLE, evidence.formId(), evidence.formVersion(), true);
    }

    /** Uses the stored bytes only to establish readability; it cannot classify a PDF without a detector. */
    public static TypeResult inspectStoredFile(C02FileStorage storage, DocumentPort.DocumentMetadata file) {
        if (storage == null || file == null) return new TypeResult(TypeStatus.TYPE_UNVERIFIABLE, ReasonCode.FILE_UNREADABLE, null, null, true);
        try (InputStream input = storage.open(file.storageReference())) {
            byte[] header = input.readNBytes(5);
            if (header.length != 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D' || header[3] != 'F' || header[4] != '-')
                return new TypeResult(TypeStatus.TYPE_UNVERIFIABLE, ReasonCode.FILE_UNREADABLE, null, null, true);
            return classify(TemplateEvidence.unavailable());
        } catch (Exception ignored) { return new TypeResult(TypeStatus.TYPE_UNVERIFIABLE, ReasonCode.FILE_UNREADABLE, null, null, true); }
    }

    public static Result verify(DocumentRef ref, C02PowerOfAttorneyOcrComparison.CaseFacts facts,
                                TemplateEvidence evidence, C02PowerOfAttorneyOcrComparison.OcrDocument ocr) {
        if (ref == null || facts == null || ref.caseId() != facts.caseId() || ref.revision() != facts.revision() || ref.documentFileId() != facts.documentFileId())
            throw new IllegalArgumentException("OCR facts must belong to the exact Case, revision, and document file.");
        TypeResult type = classify(evidence);
        if (type.status() != TypeStatus.TYPE_MATCHED)
            return new Result(ref, type, null, type.status() == TypeStatus.TYPE_MISMATCH ? ReasonCode.TYPE_NOT_MATCHED : ReasonCode.TYPE_UNVERIFIABLE, true);
        if (ocr == null || !"SUCCEEDED".equals(ocr.extractionStatus()))
            return new Result(ref, type, C02PowerOfAttorneyOcrComparison.compare(facts, ocr), ReasonCode.OCR_FAILED, true);
        return new Result(ref, type, C02PowerOfAttorneyOcrComparison.compare(facts, ocr), null, true);
    }
}
