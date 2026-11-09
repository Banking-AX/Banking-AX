package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class C02PowerOfAttorneyDocumentVerificationTest {
    private final C02PowerOfAttorneyDocumentVerification.DocumentRef ref = new C02PowerOfAttorneyDocumentVerification.DocumentRef(10, 6, 30, 2);
    private final C02PowerOfAttorneyOcrComparison.CaseFacts facts = new C02PowerOfAttorneyOcrComparison.CaseFacts(10, 6, 30, "Decedent", List.of("Agent"), List.of("Delegator"));
    private final C02PowerOfAttorneyDocumentVerification.TemplateEvidence matched = new C02PowerOfAttorneyDocumentVerification.TemplateEvidence(true, false, "5-101-0337", "2014-11-28", Set.of("depositor", "account", "agent", "delegator", "delegation-purpose"));
    private C02PowerOfAttorneyOcrComparison.OcrDocument ocr(String agent, List<C02PowerOfAttorneyOcrComparison.Person> delegators, String status) { return new C02PowerOfAttorneyOcrComparison.OcrDocument("Decedent", List.of(new C02PowerOfAttorneyOcrComparison.Account("999")), new C02PowerOfAttorneyOcrComparison.Person(agent), delegators, status); }

    @Test void matchedTemplateAndOcrComparesNamesButLeavesAccountForStaff() {
        var result = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, matched, ocr("Agent", List.of(new C02PowerOfAttorneyOcrComparison.Person("Delegator")), "SUCCEEDED"));
        assertEquals(C02PowerOfAttorneyDocumentVerification.TypeStatus.TYPE_MATCHED, result.type().status());
        assertEquals(C02PowerOfAttorneyOcrComparison.Outcome.AUTO_MATCHED, result.comparison().fields().get(0).outcome());
        assertEquals(C02PowerOfAttorneyOcrComparison.Outcome.STAFF_REQUIRED, result.comparison().fields().get(3).outcome());
    }
    @Test void mismatchMissingDelegatorAndFailedOcrNeverCreateAutomaticMatch() {
        var mismatch = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, matched, ocr("Other", List.of(new C02PowerOfAttorneyOcrComparison.Person("Delegator")), "SUCCEEDED"));
        assertEquals(C02PowerOfAttorneyOcrComparison.Outcome.MISMATCH, mismatch.comparison().fields().get(1).outcome());
        var missing = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, matched, ocr("Agent", List.of(), "SUCCEEDED"));
        assertEquals(C02PowerOfAttorneyOcrComparison.Outcome.STAFF_REQUIRED, missing.comparison().fields().get(2).outcome());
        var failed = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, matched, ocr("Agent", List.of(), "FAILED"));
        assertEquals(C02PowerOfAttorneyOcrComparison.Outcome.OCR_UNSUPPORTED, failed.comparison().fields().get(0).outcome());
    }
    @Test void unrelatedPdfWithoutTrustedEvidenceIsUnverifiableNotMatched() {
        var result = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, C02PowerOfAttorneyDocumentVerification.TemplateEvidence.unavailable(), ocr("Agent", List.of(new C02PowerOfAttorneyOcrComparison.Person("Delegator")), "SUCCEEDED"));
        assertEquals(C02PowerOfAttorneyDocumentVerification.TypeStatus.TYPE_UNVERIFIABLE, result.type().status());
        assertNull(result.comparison());
        var unsupported = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, new C02PowerOfAttorneyDocumentVerification.TemplateEvidence(true, false, "other-form", "unknown", Set.of("depositor")), null);
        assertEquals(C02PowerOfAttorneyDocumentVerification.TypeStatus.TYPE_UNVERIFIABLE, unsupported.type().status());
    }
    @Test void trustedNonTemplateIsMismatchAndForeignOrStaleReferenceIsRejected() {
        var mismatch = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, new C02PowerOfAttorneyDocumentVerification.TemplateEvidence(true, true, null, null, Set.of()), null);
        assertEquals(C02PowerOfAttorneyDocumentVerification.TypeStatus.TYPE_MISMATCH, mismatch.type().status());
        var foreign = new C02PowerOfAttorneyOcrComparison.CaseFacts(11, 6, 30, "Decedent", List.of("Agent"), List.of("Delegator"));
        assertThrows(IllegalArgumentException.class, () -> C02PowerOfAttorneyDocumentVerification.verify(ref, foreign, matched, null));
        var stale = new C02PowerOfAttorneyDocumentVerification.DocumentRef(10, 5, 30, 1);
        assertThrows(IllegalArgumentException.class, () -> C02PowerOfAttorneyDocumentVerification.verify(stale, facts, matched, null));
    }
    @Test void sameSyntheticInputIsDeterministic() {
        var first = C02PowerOfAttorneyDocumentVerification.verify(ref, facts, matched, ocr("Agent", List.of(new C02PowerOfAttorneyOcrComparison.Person("Delegator")), "SUCCEEDED"));
        assertEquals(first, C02PowerOfAttorneyDocumentVerification.verify(ref, facts, matched, ocr("Agent", List.of(new C02PowerOfAttorneyOcrComparison.Person("Delegator")), "SUCCEEDED")));
    }
}
