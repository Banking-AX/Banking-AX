package com.bankingax.task.c02;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
/** Safe default: only allow reviewed official-form codes; no file bytes leave this process. */
@Component @ConditionalOnProperty(name="app.c02.ocr-provider",havingValue="mock",matchIfMissing=true)
public class MockOcrAdapter implements OcrPort {
 /**
  * C-02 never OCRs the general family/basic certificates.  The only C-02
  * upload slot that may be OCR'd in this PoC is the reviewed, official
  * delegation form for an absent heir (C02-02); V11 records that policy in
  * {@code document_type}.  Results remain staff-reference material only.
  */
 private static final Set<String> ALLOWLIST=Set.of("ABSENT_HEIR_POWER_OF_ATTORNEY");
 public OcrResult extract(ExtractRequest r){return ALLOWLIST.contains(r.documentType())?new OcrResult("MOCK","SUCCEEDED",Map.of("documentType",r.documentType(),"manualVerificationRequired",true),null):new OcrResult("NONE","NOT_APPLICABLE",Map.of(),null);}
}
