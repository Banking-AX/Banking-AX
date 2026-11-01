package com.bankingax.task.c02;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
/** Placeholder boundary. It deliberately never calls CLOVA until an explicit provider/key contract is configured. */
@Component @ConditionalOnProperty(name="app.c02.ocr-provider",havingValue="clova")
public class ClovaOcrHttpAdapter implements OcrPort { public OcrResult extract(ExtractRequest request){return new OcrResult("CLOVA_GENERAL","FAILED_NEEDS_STAFF",Map.of(),"CLOVA_OCR_NOT_CONFIGURED");} }
