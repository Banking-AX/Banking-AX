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
    void configured_fixture_uses_clova_normalized_field_shape_and_marks_synthetic_source() {
        var result = new MockOcrAdapter("Synthetic Grantor Fixture", "Synthetic Agent Fixture", 0.87)
                .extract(new OcrPort.ExtractRequest(3L, "ABSENT_HEIR_POWER_OF_ATTORNEY", "opaque.pdf"));

        assertEquals("SYNTHETIC_MOCK_FIXTURE", result.fields().get("source"));
        var fields = (java.util.List<?>) result.fields().get("fields");
        assertEquals(2, fields.size());
        assertEquals("grantor_names", ((java.util.Map<?, ?>) fields.get(0)).get("name"));
        assertEquals("Synthetic Grantor Fixture", ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) fields.get(0)).get("value")).get("text"));
        assertEquals(0.87, ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) fields.get(0)).get("value")).get("confidence"));
    }

    @Test
    void leaves_general_certificates_out_of_the_ocr_path() {
        var result = adapter.extract(new OcrPort.ExtractRequest(2L, "DECEDENT_BASIC_CERTIFICATE", "opaque.pdf"));

        assertEquals("NONE", result.method());
        assertEquals("NOT_APPLICABLE", result.status());
    }
}
