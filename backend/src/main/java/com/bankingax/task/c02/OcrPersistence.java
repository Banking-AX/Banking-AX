package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-02 OCR audit boundary.  It reads only a current file from the private store and
 * records its outcome without changing customer submission or final staff decisions.
 */
@Service
@ConditionalOnDataSource
public class OcrPersistence {
    private static final String POWER_OF_ATTORNEY = "ABSENT_HEIR_POWER_OF_ATTORNEY";
    private final JdbcTemplate jdbc; private final OcrPort ocr; private final ObjectMapper json; private final C02FileStorage storage;
    /** Compatibility constructor for read-only migration tests; extraction stays unavailable without private storage. */
    public OcrPersistence(JdbcTemplate jdbc,OcrPort ocr,ObjectMapper json){this(jdbc,ocr,json,null);}
    @Autowired public OcrPersistence(JdbcTemplate jdbc,OcrPort ocr,ObjectMapper json,C02FileStorage storage){this.jdbc=jdbc;this.ocr=ocr;this.json=json;this.storage=storage;}

    @Transactional
    public void extract(long fileId,long caseId,int verificationRevision,String documentType,String ignoredStorageReference){
        FileRef ref=requireCurrentFile(fileId,caseId,verificationRevision,documentType);
        if(existing(fileId,verificationRevision)) return;
        OcrPort.OcrResult result;
        if(!POWER_OF_ATTORNEY.equals(ref.documentType())) result=new OcrPort.OcrResult("NONE","NOT_APPLICABLE",Map.of(),null);
        else if(storage==null) result=new OcrPort.OcrResult("NONE","FAILED_NEEDS_STAFF",Map.of(),"OCR_STORAGE_UNAVAILABLE");
        else try(InputStream input=storage.open(ref.storageKey())) {
            result=ocr.extract(new OcrPort.ExtractRequest(fileId,ref.documentType(),ref.storageKey(),ref.originalName(),ref.contentType(),input.readAllBytes()));
        } catch(Exception e) { result=new OcrPort.OcrResult("NONE","FAILED_NEEDS_STAFF",Map.of(),"OCR_STORAGE_UNAVAILABLE"); }
        insert(fileId,caseId,verificationRevision,result);
        persistVerification(fileId,caseId,verificationRevision,ref.version(),result);
    }

    private FileRef requireCurrentFile(long fileId,long caseId,int revision,String claimedType){
        List<FileRef> rows=jdbc.query("""
            select df.storage_path,df.original_name,df.content_type,df.version,rd.document_type_code
              from document_file df join required_document rd on rd.id=df.required_document_id
              join work_item wi on wi.id=rd.work_item_id join service_case sc on sc.work_item_id=wi.id
             where df.id=? and sc.id=? and sc.case_type='INHERITANCE_DEPOSIT'
               and wi.task_code='INHERITANCE_DEPOSIT' and sc.revision=?
               and df.is_latest=true and df.deleted_at is null and df.case_revision<=sc.revision
            """,(rs,n)->new FileRef(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5)),fileId,caseId,revision);
        if(rows.size()!=1 || !rows.get(0).documentType().equals(claimedType)) throw new DomainException("OCR_DOCUMENT_MISMATCH","OCR target is not a current C-02 document slot.",true);
        return rows.get(0);
    }
    private boolean existing(long fileId,int revision){Integer count=jdbc.queryForObject("select count(*) from document_extraction where document_file_id=? and revision=?",Integer.class,fileId,revision);return count!=null&&count>0;}
    private void insert(long fileId,long caseId,int revision,OcrPort.OcrResult result){try{jdbc.update("insert into document_extraction(document_file_id,case_id,revision,method,status,extracted,failure_reason) values(?,?,?,?,?,cast(? as json),?)",fileId,caseId,revision,result.method(),result.status(),json.writeValueAsString(result.fields()),result.failureReason());}catch(Exception e){throw new DomainException("OCR_PERSISTENCE_FAILED","OCR result could not be persisted.",true);}}
    /** Persist only Spring's deterministic document-type and field comparison facts. */
    private void persistVerification(long fileId,long caseId,int revision,int documentVersion,OcrPort.OcrResult result) {
        if (!POWER_OF_ATTORNEY.equals(jdbc.queryForObject("select rd.document_type_code from document_file df join required_document rd on rd.id=df.required_document_id where df.id=?",String.class,fileId))) return;
        Long runId=jdbc.query("select id from agent_review_run where case_id=? and revision=? and check_type='C02_OCR_PRECHECK'",rs->rs.next()?rs.getLong(1):null,caseId,revision);
        if(runId==null){jdbc.update("insert into agent_review_run(case_id,revision,check_type,status,attempt_count,rule_version,report,started_at,finished_at) values(?,?, 'C02_OCR_PRECHECK','SUCCEEDED',1,'c02-ocr-v1',cast(? as json),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",caseId,revision,asJson(Map.of("ocrMethod",result.method())));runId=jdbc.queryForObject("select last_insert_id()",Long.class);}
        Integer duplicate=jdbc.queryForObject("select count(*) from review_item where agent_review_run_id=? and document_file_id=? and category='C02_POA_DOCUMENT_TYPE'",Integer.class,runId,fileId);
        if(duplicate!=null&&duplicate>0)return;
        C02PowerOfAttorneyDocumentVerification.Result verified=verify(caseId,revision,fileId,documentVersion,result);
        String message=switch(verified.type().status()) { case TYPE_MATCHED -> "Supported power-of-attorney structure was confirmed."; case TYPE_MISMATCH -> "The trusted detector identified a non-power-of-attorney document."; case TYPE_UNVERIFIABLE -> "Document type could not be established from the OCR response. Original document review is required."; };
        jdbc.update("insert into review_item(case_id,revision,agent_review_run_id,source,category,result,basis_doc_id,document_file_id,message,evidence) values(?,?,?,'SPRING_RULE','C02_POA_DOCUMENT_TYPE',?,'C02-02',?,?,cast(? as json))",caseId,revision,runId,verified.type().status().name(),fileId,message,asJson(Map.of("documentVersion",documentVersion,"ocrStatus",result.status(),"reason",verified.type().reason().name())));
        if(verified.comparison()!=null) for(C02PowerOfAttorneyOcrComparison.FieldResult field:verified.comparison().fields()) {
            jdbc.update("insert into review_item(case_id,revision,agent_review_run_id,source,category,result,basis_doc_id,document_file_id,message,evidence) values(?,?,?,'SPRING_RULE',?,?, 'C02-02',?,?,cast(? as json))",caseId,revision,runId,"C02_POA_"+field.field(),field.outcome().name(),fileId,field.reason(),asJson(Map.of("documentVersion",documentVersion,"actual",field.extractedValue()==null?"":field.extractedValue(),"expected",field.expectedValue()==null?"":field.expectedValue())));
        }
    }
    private C02PowerOfAttorneyDocumentVerification.Result verify(long caseId,int revision,long fileId,int version,OcrPort.OcrResult result) {
        try {
            com.fasterxml.jackson.databind.JsonNode app=json.readTree(jdbc.queryForObject("select application from service_case where id=?",String.class,caseId)).path("c02");
            String decedent=app.path("decedent").path("name").asText(""); java.util.List<String> attending=new java.util.ArrayList<>(), absent=new java.util.ArrayList<>(); Map<String,String> heirBirthDates=new LinkedHashMap<>(); java.util.Set<String> repeatedNames=new java.util.HashSet<>();
            for(com.fasterxml.jackson.databind.JsonNode heir:app.path("heirs")) { String name=heir.path("name").asText(""); String visit=heir.path("visitStatus").asText(""); if("ATTENDING".equals(visit)) attending.add(name); else if("ABSENT".equals(visit)||"UNCONFIRMED_ABSENT".equals(visit)) absent.add(name); String key=C02PowerOfAttorneyOcrComparison.normalizedName(name); String birthDate=heir.path("birthDate").asText(""); if(heirBirthDates.containsKey(key)||repeatedNames.contains(key)){repeatedNames.add(key);heirBirthDates.remove(key);} else if(!birthDate.isBlank()) heirBirthDates.put(key,birthDate); }
            C02PowerOfAttorneyOcrComparison.CaseFacts facts=new C02PowerOfAttorneyOcrComparison.CaseFacts(caseId,revision,fileId,decedent,app.path("decedent").path("birthDate").asText(""),attending,absent,heirBirthDates);
            com.fasterxml.jackson.databind.JsonNode root=json.valueToTree(result.fields()); com.fasterxml.jackson.databind.JsonNode standard=root.path("standard");
            java.util.List<C02PowerOfAttorneyOcrComparison.Account> accounts=new java.util.ArrayList<>(); for(com.fasterxml.jackson.databind.JsonNode n:standard.path("accounts")) accounts.add(new C02PowerOfAttorneyOcrComparison.Account(n.path("accountNo").asText("")));
            java.util.List<C02PowerOfAttorneyOcrComparison.Person> delegators=new java.util.ArrayList<>(); for(com.fasterxml.jackson.databind.JsonNode n:standard.path("delegators")) delegators.add(new C02PowerOfAttorneyOcrComparison.Person(n.path("name").asText(""),n.path("birthDate").asText("")));
            C02PowerOfAttorneyOcrComparison.OcrDocument ocrDoc=new C02PowerOfAttorneyOcrComparison.OcrDocument(standard.path("depositorName").asText(""),standard.path("reference").path("depositorBirthDate").asText(""),accounts,new C02PowerOfAttorneyOcrComparison.Person(standard.path("agent").path("name").asText(""),standard.path("reference").path("agentBirthDate").asText("")),delegators,"SUCCEEDED".equals(result.status())?"SUCCEEDED":"FAILED");
            java.util.Set<String> structure=new java.util.HashSet<>(); if(!standard.path("depositorName").asText("").isBlank())structure.add("depositor");if(!accounts.isEmpty())structure.add("account");if(!standard.path("agent").path("name").asText("").isBlank())structure.add("agent");if(!delegators.isEmpty())structure.add("delegator"); com.fasterxml.jackson.databind.JsonNode fields=root.path("fields");if(hasText(fields,"delegation_purpose","위임취지","위임내용"))structure.add("delegation-purpose");
            boolean trusted="SUCCEEDED".equals(result.status())&&root.path("matchedTemplateId").asLong(-1)>0;
            var evidence=new C02PowerOfAttorneyDocumentVerification.TemplateEvidence(trusted,false,C02PowerOfAttorneyDocumentVerification.FORM_ID,C02PowerOfAttorneyDocumentVerification.FORM_VERSION,structure);
            return C02PowerOfAttorneyDocumentVerification.verify(new C02PowerOfAttorneyDocumentVerification.DocumentRef(caseId,revision,fileId,version),facts,evidence,ocrDoc);
        } catch(Exception e) { return C02PowerOfAttorneyDocumentVerification.verify(new C02PowerOfAttorneyDocumentVerification.DocumentRef(caseId,revision,fileId,version),new C02PowerOfAttorneyOcrComparison.CaseFacts(caseId,revision,fileId,"",List.of(),List.of()),C02PowerOfAttorneyDocumentVerification.TemplateEvidence.unavailable(),null); }
    }
    private static boolean hasText(com.fasterxml.jackson.databind.JsonNode fields,String... names){for(String name:names)if(!fields.path(name).asText("").isBlank())return true;return false;}
    private String asJson(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new DomainException("OCR_PERSISTENCE_FAILED","OCR evidence could not be serialized.",true);}}

    @Transactional(readOnly=true)
    /** OCR belongs to a file, not a Case revision: uploading another document must not hide the latest file's result. */
    public List<Map<String,Object>> snapshot(long caseId){return jdbc.query("""
        select e.document_file_id,e.method,e.status,e.extracted,e.failure_reason,df.version
          from document_extraction e join document_file df on df.id=e.document_file_id
          join required_document rd on rd.id=df.required_document_id join work_item wi on wi.id=rd.work_item_id
          join service_case sc on sc.work_item_id=wi.id
         where e.case_id=? and sc.id=?
           and e.id=(select max(latest.id) from document_extraction latest where latest.document_file_id=e.document_file_id)
           and df.is_latest=true and df.deleted_at is null and df.case_revision<=sc.revision
         order by e.id
        """,(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();row.put("documentFileId",rs.getLong(1));row.put("method",rs.getString(2));row.put("status",rs.getString(3));row.put("extracted",rs.getString(4));row.put("failureReason",rs.getString(5));row.put("documentVersion",rs.getInt(6));row.put("manualVerificationRequired",true);return row;},caseId,caseId);}
    /** Extracted values are for staff review; a customer sees only the status of each document. */
    public Snapshot customerSnapshot(long caseId,int revision){List<Map<String,Object>> rows=snapshot(caseId).stream().map(row->{Map<String,Object> visible=new LinkedHashMap<>(row);visible.remove("extracted");return visible;}).toList();return new Snapshot(caseId,revision,true,rows);}
    public Snapshot currentSnapshot(long caseId,int revision){return new Snapshot(caseId,revision,true,snapshot(caseId));}
    @Transactional(readOnly=true) public List<Map<String,Object>> reviewItems(long caseId){return jdbc.query("select ri.source,ri.category,ri.result,ri.message,ri.document_file_id from review_item ri join agent_review_run ar on ar.id=ri.agent_review_run_id join document_file df on df.id=ri.document_file_id where ri.case_id=? and ar.check_type='C02_OCR_PRECHECK' and df.is_latest=true and df.deleted_at is null order by ri.id",(rs,n)->Map.<String,Object>of("source",rs.getString(1),"category",rs.getString(2),"result",rs.getString(3),"message",rs.getString(4)==null?"":rs.getString(4),"documentFileId",rs.getObject(5)==null?0L:rs.getLong(5)),caseId);}
    public record Snapshot(long caseId,int revision,boolean manualVerificationRequired,List<Map<String,Object>> documents){}
    private record FileRef(String storageKey,String originalName,String contentType,int version,String documentType){}
}
