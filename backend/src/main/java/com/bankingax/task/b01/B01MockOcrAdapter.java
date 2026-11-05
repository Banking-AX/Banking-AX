package com.bankingax.task.b01;

import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Resolves reviewed synthetic fixtures by SHA-256; filenames and client fields are never trusted. */
@Component
@ConditionalOnProperty(name = "app.b01.ocr-provider", havingValue = "mock", matchIfMissing = true)
final class B01MockOcrAdapter implements B01OcrPort {
    static final String DOCUMENT_TYPE = "B01_REPAYMENT_ORDER_APPLICATION";
    private final Map<String, Fields> fixtures;

    B01MockOcrAdapter() {
        this.fixtures = Map.of(checksum("b01/mock-ocr/b01-t01-application.pdf"),
                new Fields(List.of(new LoanField("MOCK-B01-T01", new BigDecimal("26500000"),
                        LocalDate.of(2026, 8, 31))), "PRINCIPAL_FIRST"));
    }

    @Override
    public OcrResult extract(ExtractRequest request) {
        Fields fields = DOCUMENT_TYPE.equals(request.documentTypeCode()) ? fixtures.get(request.checksum()) : null;
        SignatureChecks signatures = new SignatureChecks("NOT_VERIFIED", "NOT_VERIFIED");
        if (fields == null) {
            return new OcrResult(DOCUMENT_TYPE, null, "MOCK", "FAILED_NEEDS_STAFF",
                    new Fields(List.of(), null), signatures, false, true,
                    "MOCK_FIXTURE_NOT_REGISTERED");
        }
        VisualChecks visual = new VisualChecks("ALIGNED", "CHECKED", "UNCHECKED",
                "PRINCIPAL_FIRST", "TRACE_PRESENT", false, true, null);
        return new OcrResult(DOCUMENT_TYPE, DOCUMENT_TYPE, "MOCK", "SUCCEEDED", fields,
                signatures, false, true, null, visual);
    }

    @Override
    public String method() { return "MOCK"; }

    private static String checksum(String classpathLocation) {
        try (InputStream input = new ClassPathResource(classpathLocation).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception exception) {
            throw new IllegalStateException("B-01 Mock OCR fixture cannot be loaded.", exception);
        }
    }
}
