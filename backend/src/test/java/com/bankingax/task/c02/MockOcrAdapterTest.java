package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MockOcrAdapterTest {
    private final MockOcrAdapter adapter = new MockOcrAdapter();

    @Test
    void extracts_only_the_reviewed_c02_delegation_form_as_mock_staff_reference() {
        var result = adapter.extract(new OcrPort.ExtractRequest(1L, "ABSENT_HEIR_POWER_OF_ATTORNEY", "opaque.pdf"));

        assertEquals("MOCK", result.method());
        assertEquals("SUCCEEDED", result.status());
        assertEquals(true, result.fields().get("manualVerificationRequired"));
    }

    @Test
    void leaves_general_certificates_out_of_the_ocr_path() {
        var result = adapter.extract(new OcrPort.ExtractRequest(2L, "DECEDENT_BASIC_CERTIFICATE", "opaque.pdf"));

        assertEquals("NONE", result.method());
        assertEquals("NOT_APPLICABLE", result.status());
    }
}
