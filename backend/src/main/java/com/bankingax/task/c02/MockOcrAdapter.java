package com.bankingax.task.c02;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 private final String grantorNames;
 private final String agentName;
 private final double confidence;

 public MockOcrAdapter() { this("", "", 0.0); }

 @Autowired
 public MockOcrAdapter(@Value("${app.c02.ocr.mock.grantor-names:}") String grantorNames,
                       @Value("${app.c02.ocr.mock.agent-name:}") String agentName,
                       @Value("${app.c02.ocr.mock.confidence:0.0}") double confidence) {
  this.grantorNames = grantorNames == null ? "" : grantorNames;
  this.agentName = agentName == null ? "" : agentName;
  this.confidence = confidence;
 }

 public OcrResult extract(ExtractRequest r){
  if(!ALLOWLIST.contains(r.documentType())) return new OcrResult("NONE","NOT_APPLICABLE",Map.of(),null);
  var fields = new ArrayList<Map<String,Object>>();
  addField(fields,"grantor_names",grantorNames);
  addField(fields,"agent_name",agentName);
  Map<String,Object> result = new java.util.LinkedHashMap<>();
  result.put("documentType", r.documentType());
  result.put("fields", fields);
  result.put("manualVerificationRequired", true);
  result.put("source", "SYNTHETIC_MOCK_FIXTURE");
  return new OcrResult("MOCK","SUCCEEDED",result,null);
 }

 private void addField(java.util.List<Map<String,Object>> fields,String name,String text) {
  if(text.isBlank()) return;
  fields.add(Map.of("name",name,"value",Map.of("text",text,"confidence",confidence)));
 }
}
