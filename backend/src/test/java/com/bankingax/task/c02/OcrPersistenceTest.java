package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OcrPersistenceTest {
    @Test void persists_clova_method_status_and_fields_only_for_latest_verified_official_pdf() {
        RecordingJdbc jdbc=new RecordingJdbc(1,1);
        OcrPort adapter=request->new OcrPort.OcrResult("CLOVA_TEMPLATE","SUCCEEDED",Map.of("fields",java.util.List.of(Map.of("name","name","value",Map.of("text","synthetic"))),"manualVerificationRequired",true),null);
        new OcrPersistence(jdbc,adapter,new ObjectMapper()).extract(12,7,3,"ABSENT_HEIR_POWER_OF_ATTORNEY","opaque-key.pdf");
        assertEquals("CLOVA_TEMPLATE",jdbc.values[3]); assertEquals("SUCCEEDED",jdbc.values[4]);
        assertTrue(((String)jdbc.values[5]).contains("manualVerificationRequired"));
    }

    @Test void stale_file_revision_is_rejected_before_provider_call() {
        RecordingJdbc jdbc=new RecordingJdbc(0,1); OcrPort adapter=mock(OcrPort.class);
        assertThrows(DomainException.class,()->new OcrPersistence(jdbc,adapter,new ObjectMapper())
            .extract(12,7,2,"ABSENT_HEIR_POWER_OF_ATTORNEY","opaque-key.pdf"));
        verifyNoInteractions(adapter); assertNull(jdbc.values);
    }

    @Test void non_official_or_unapproved_types_are_recorded_not_applicable_without_provider_call() {
        RecordingJdbc jdbc=new RecordingJdbc(1,0); OcrPort adapter=mock(OcrPort.class);
        new OcrPersistence(jdbc,adapter,new ObjectMapper()).extract(13,7,3,"DECEDENT_BASIC_CERTIFICATE","opaque.pdf");
        verifyNoInteractions(adapter); assertEquals("NONE",jdbc.values[3]); assertEquals("NOT_APPLICABLE",jdbc.values[4]);
    }

    @Test void provider_exception_is_manual_review_failure_not_mock_success() {
        RecordingJdbc jdbc=new RecordingJdbc(1,1); OcrPort adapter=request->{throw new IllegalStateException("secret response must not leak");};
        new OcrPersistence(jdbc,adapter,new ObjectMapper()).extract(12,7,3,"ABSENT_HEIR_POWER_OF_ATTORNEY","opaque-key.pdf");
        assertEquals("NONE",jdbc.values[3]); assertEquals("FAILED_NEEDS_STAFF",jdbc.values[4]);
        assertEquals("OCR_UNAVAILABLE",jdbc.values[6]); assertTrue(((String)jdbc.values[5]).contains("manualVerificationRequired"));
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final int currentFile; private final int approved; private Object[] values;
        private RecordingJdbc(int currentFile,int approved){this.currentFile=currentFile;this.approved=approved;}
        @Override public <T> T queryForObject(String sql,Class<T> requiredType,Object... args) {
            if(sql.contains("document_extraction where")) return requiredType.cast(0);
            return requiredType.cast(sql.contains("document_type dt")?approved:currentFile);
        }
        @Override public int update(String sql,Object... args){values=args;return 1;}
    }
}
