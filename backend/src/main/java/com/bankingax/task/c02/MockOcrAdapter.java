package com.bankingax.task.c02;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
/** Safe default: only allow reviewed official-form codes; no file bytes leave this process. */
@Component @ConditionalOnProperty(name="app.c02.ocr-provider",havingValue="mock",matchIfMissing=true)
public class MockOcrAdapter implements OcrPort {
 private static final Set<String> ALLOWLIST=Set.of("SHINHAN_INHERITANCE_APPLICATION");
 public OcrResult extract(ExtractRequest r){return ALLOWLIST.contains(r.documentType())?new OcrResult("MOCK","SUCCEEDED",Map.of("documentType",r.documentType(),"manualVerificationRequired",true),null):new OcrResult("NONE","NOT_APPLICABLE",Map.of(),null);}
}
