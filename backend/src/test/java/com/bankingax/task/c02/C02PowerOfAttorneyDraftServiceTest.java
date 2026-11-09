package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class C02PowerOfAttorneyDraftServiceTest {
    private static final String FONT = "C:/Windows/Fonts/malgun.ttf";

    @Test
    void createsUnsignedDraftFromOwnedDraftSnapshotWithoutPersistingDocuments() throws Exception {
        C02IntakeService intake = mock(C02IntakeService.class);
        when(intake.customerCaseSnapshot(42L, 700L)).thenReturn(snapshot("DRAFT"));
        C02PowerOfAttorneyDraftService service = new C02PowerOfAttorneyDraftService(intake, FONT);

        byte[] pdf = service.create(42L, 700L, request("absent-1"));

        assertTrue(pdf.length > 1_000);
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertEquals(1, document.getNumberOfPages());
            assertTrue(text.contains("김영준"));
            assertTrue(text.contains("김민수"));
            assertTrue(text.contains("김하나"));
            assertTrue(text.contains("99900000000001"));
            assertTrue(text.contains("2026"));
        }
    }

    @Test
    void rejectsNonDraftAndNonDocumentDelegator() {
        C02IntakeService intake = mock(C02IntakeService.class);
        when(intake.customerCaseSnapshot(42L, 700L)).thenReturn(snapshot("SUBMITTED"));
        C02PowerOfAttorneyDraftService service = new C02PowerOfAttorneyDraftService(intake, FONT);
        DomainCode state = codeOf(() -> service.create(42L, 700L, request("absent-1")));
        assertEquals("INVALID_STATE", state.value());

        when(intake.customerCaseSnapshot(42L, 700L)).thenReturn(snapshot("DRAFT"));
        DomainCode slot = codeOf(() -> service.create(42L, 700L, request("wrong-heir")));
        assertEquals("DOCUMENT_SLOT_UNKNOWN", slot.value());
    }

    @Test
    void rejectsAnIdentifierThatWouldOtherwiseBeSilentlyTruncated() {
        C02IntakeService intake = mock(C02IntakeService.class);
        when(intake.customerCaseSnapshot(42L, 700L)).thenReturn(snapshot("DRAFT", "매우긴피상속인성명매우긴피상속인성명매우긴피상속인성명매우긴피상속인성명"));
        DomainCode error = codeOf(() -> new C02PowerOfAttorneyDraftService(intake, FONT).create(42L, 700L, request("absent-1")));
        assertEquals("POA_FIELD_TOO_LONG", error.value());
    }

    @Test
    void rejectsAnOverlongAccountNumberInsteadOfCuttingIt() {
        C02IntakeService intake = mock(C02IntakeService.class);
        when(intake.customerCaseSnapshot(42L, 700L)).thenReturn(snapshot("DRAFT"));
        var overlong = new C02PowerOfAttorneyDraftService.Request("absent-1", List.of("1234567890123456789012345678901234567890"), "보통예금",
                "서울특별시 중구 테스트로 12", "서울특별시 중구 테스트로 34", "자녀", LocalDate.of(2026, 11, 6));
        DomainCode error = codeOf(() -> new C02PowerOfAttorneyDraftService(intake, FONT).create(42L, 700L, overlong));
        assertEquals("POA_FIELD_TOO_LONG", error.value());
    }

    /** Leaves only synthetic data under target/ so the generated overlay can be visually checked. */
    @Test
    void rendersSyntheticDraftForVisualCoordinateVerification() throws Exception {
        C02IntakeService intake = mock(C02IntakeService.class);
        when(intake.customerCaseSnapshot(42L, 700L)).thenReturn(snapshot("DRAFT"));
        byte[] pdf = new C02PowerOfAttorneyDraftService(intake, FONT).create(42L, 700L, request("absent-1"));
        Path output = Path.of("target", "c02-poa-draft-preview");
        Files.createDirectories(output);
        Files.write(output.resolve("synthetic-inheritance-poa-draft.pdf"), pdf);
        try (var document = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 144);
            assertTrue(ImageIO.write(page, "png", output.resolve("page-1.png").toFile()));
        }
    }

    private static C02IntakeService.CustomerCaseSnapshot snapshot(String caseStatus) { return snapshot(caseStatus, "김영준"); }

    private static C02IntakeService.CustomerCaseSnapshot snapshot(String caseStatus, String decedentName) {
        var heirs = List.of(
                new C02IntakeService.HeirView("agent-1", "김민수", LocalDate.of(1997, 4, 12), "자녀", true, "ATTENDING", "NONE"),
                new C02IntakeService.HeirView("absent-1", "김하나", LocalDate.of(1994, 1, 3), "자녀", false, "ABSENT", "DOCUMENT"));
        return new C02IntakeService.CustomerCaseSnapshot(700L, 900L, 3, caseStatus, "PENDING", false,
                List.of(), List.of(), null,
                new C02IntakeService.CaseIntakeView(decedentName, LocalDate.of(1955, 1, 1), LocalDate.of(2020, 1, 1), 6_000_000L, heirs),
                null, null);
    }

    private static C02PowerOfAttorneyDraftService.Request request(String delegatorPersonRef) {
        return new C02PowerOfAttorneyDraftService.Request(delegatorPersonRef, List.of("99900000000001"), "보통예금",
                "서울특별시 중구 테스트로 12", "서울특별시 중구 테스트로 34", "자녀", LocalDate.of(2026, 11, 6));
    }

    private record DomainCode(String value) { }
    private static DomainCode codeOf(org.junit.jupiter.api.function.Executable call) {
        var error = assertThrows(com.bankingax.core.api.DomainException.class, call);
        return new DomainCode(error.code());
    }
}
