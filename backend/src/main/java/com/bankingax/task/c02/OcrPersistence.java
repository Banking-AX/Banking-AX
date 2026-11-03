package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Best-effort OCR audit and read-only current-revision view; OCR never decides a Case. */
@Service
@ConditionalOnProperty(name = "app.c02.document-port", havingValue = "jpa")
public class OcrPersistence {
    private final JdbcTemplate jdbc;
    private final OcrPort ocr;
    private final ObjectMapper json;
    private final C02OcrComparisonService comparison;

    public OcrPersistence(JdbcTemplate jdbc, OcrPort ocr, ObjectMapper json) {
        this(jdbc, ocr, json, new C02OcrComparisonService(jdbc, 0.90));
    }

    @Autowired
    public OcrPersistence(JdbcTemplate jdbc, OcrPort ocr, ObjectMapper json, C02OcrComparisonService comparison) {
        this.jdbc = jdbc;
        this.ocr = ocr;
        this.json = json;
        this.comparison = comparison;
    }

    public synchronized void extract(long fileId, long caseId, int revision, String type, String storage) {
        Integer currentFile = jdbc.queryForObject("""
                select count(*) from document_file df
                join required_document rd on rd.id=df.required_document_id
                join service_case sc on sc.work_item_id=rd.work_item_id
                join work_item wi on wi.id=rd.work_item_id
                join case_revision cr on cr.case_id=sc.id and cr.revision=sc.revision
                where df.id=? and sc.id=? and sc.case_type='INHERITANCE_DEPOSIT'
                  and wi.task_code='INHERITANCE_DEPOSIT' and sc.revision=?
                  and df.case_revision=? and df.is_latest=true and df.deleted_at is null
                  and rd.document_type_code=? and df.storage_path=?
                """, Integer.class, fileId, caseId, revision, revision, type, storage);
        if(currentFile==null || currentFile!=1)
            throw new DomainException("VERSION_CONFLICT","OCR is permitted only for the uploaded file on the current Case revision.",true);
        Integer alreadyProcessed=jdbc.queryForObject("select count(*) from document_extraction where document_file_id=? and case_id=? and revision=?",
                Integer.class,fileId,caseId,revision);
        if(alreadyProcessed!=null && alreadyProcessed>0) return;

        Integer approvedOfficialPdf = jdbc.queryForObject("""
                select count(*) from document_file df
                join required_document rd on rd.id=df.required_document_id
                join document_type dt on dt.code=rd.document_type_code
                where df.id=? and rd.document_type_code=? and dt.is_active=true
                  and dt.source_kind='SHINHAN_OFFICIAL' and dt.source_verified=true and dt.ocr_enabled=true
                  and df.content_type='application/pdf'
                """, Integer.class, fileId, ClovaTemplateOcrAdapter.ALLOWED_DOCUMENT_TYPE);
        if(approvedOfficialPdf==null || approvedOfficialPdf!=1 || !ClovaTemplateOcrAdapter.ALLOWED_DOCUMENT_TYPE.equals(type)) {
            persist(fileId,caseId,revision,type,storage,new OcrPort.OcrResult("NONE","NOT_APPLICABLE",java.util.Map.of("manualVerificationRequired",true),null));
            return;
        }

        OcrPort.OcrResult result;
        try {
            result=ocr.extract(new OcrPort.ExtractRequest(fileId, type, storage));
        } catch (Exception exception) {
            // OCR is auxiliary. Keep the upload; never turn a provider error into a mock success.
            result=new OcrPort.OcrResult("NONE","FAILED_NEEDS_STAFF",java.util.Map.of("manualVerificationRequired",true),"OCR_UNAVAILABLE");
        }
        if(result==null || !java.util.Set.of("NONE","MOCK","CLOVA_TEMPLATE","CLOVA_GENERAL").contains(result.method())
                || !java.util.Set.of("NOT_APPLICABLE","SUCCEEDED","FAILED_NEEDS_STAFF").contains(result.status()))
            result=new OcrPort.OcrResult("NONE","FAILED_NEEDS_STAFF",java.util.Map.of("manualVerificationRequired",true),"OCR_INVALID_RESULT");
        persist(fileId,caseId,revision,type,storage,result);
    }

    private void persist(long fileId,long caseId,int revision,String type,String storage,OcrPort.OcrResult result) {
        try {
            int inserted=jdbc.update("""
                    insert into document_extraction(document_file_id,case_id,revision,method,status,extracted,failure_reason)
                    select ?,?,?,?,?,cast(? as json),?
                    where exists (
                      select 1 from document_file df
                      join required_document rd on rd.id=df.required_document_id
                      join service_case sc on sc.work_item_id=rd.work_item_id
                      join work_item wi on wi.id=rd.work_item_id
                      join case_revision cr on cr.case_id=sc.id and cr.revision=sc.revision
                      where df.id=? and sc.id=? and sc.case_type='INHERITANCE_DEPOSIT'
                        and wi.task_code='INHERITANCE_DEPOSIT' and sc.revision=?
                        and df.case_revision=? and df.is_latest=true and df.deleted_at is null
                        and rd.document_type_code=? and df.storage_path=?
                    )
                    """, fileId, caseId, revision, result.method(), result.status(),
                    json.writeValueAsString(result.fields()==null?java.util.Map.of():result.fields()), result.failureReason(),
                    fileId, caseId, revision, revision, type, storage);
            if(inserted==0) throw new DomainException("VERSION_CONFLICT","Case revision changed while OCR was running; stale extraction was discarded.",true);
        } catch(DomainException domainFailure) {
            throw domainFailure;
        } catch(DuplicateKeyException duplicate) {
            // A second server may have completed this exact file/revision concurrently.
            Integer existing=jdbc.queryForObject("select count(*) from document_extraction where document_file_id=? and case_id=? and revision=?",Integer.class,fileId,caseId,revision);
            if(existing!=null && existing==1) return;
            throw new DomainException("OCR_RESULT_PERSISTENCE_FAILED","OCR result could not be saved.",true);
        } catch(Exception persistenceFailure) {
            // The extraction insert is one atomic statement; provider calls run outside a database transaction.
            throw new DomainException("OCR_RESULT_PERSISTENCE_FAILED","OCR result could not be saved.",true);
        }
    }

    /**
     * Reads only latest, non-deleted files bound to the Case's current revision.
     * Missing extraction rows are reported as PENDING. Every record still requires
     * human verification; extracted content is reference material only.
     */
    @Transactional(readOnly = true)
    public Snapshot currentSnapshot(long caseId, int revision) {
        Integer valid = jdbc.queryForObject("""
                select count(*) from service_case sc
                join work_item wi on wi.id=sc.work_item_id
                join case_revision cr on cr.case_id=sc.id and cr.revision=sc.revision
                where sc.id=? and sc.case_type='INHERITANCE_DEPOSIT'
                  and wi.task_code='INHERITANCE_DEPOSIT' and sc.revision=?
                """, Integer.class, caseId, revision);
        if (valid == null || valid != 1) {
            throw new DomainException("VERSION_CONFLICT", "OCR results are available only for the current C-02 Case revision.", true);
        }

        List<OcrDocument> documents = jdbc.query("""
                select df.id, df.case_revision, rd.document_type_code, h.person_ref,
                       coalesce(e.method,'NONE') as method, coalesce(e.status,'PENDING') as status,
                       cast(e.extracted as char) as extracted, e.failure_reason
                from service_case sc
                join work_item wi on wi.id=sc.work_item_id
                join required_document rd on rd.work_item_id=wi.id
                join document_file df on df.required_document_id=rd.id
                left join heir h on h.id=rd.heir_id
                left join document_extraction e on e.document_file_id=df.id
                     and e.case_id=sc.id and e.revision=?
                where sc.id=? and sc.revision=? and df.case_revision=?
                  and df.is_latest=true and df.deleted_at is null
                order by df.id
                """, (row, index) -> new OcrDocument(
                    row.getLong("id"), row.getInt("case_revision"), row.getString("document_type_code"),
                    row.getString("person_ref"), row.getString("method"), row.getString("status"),
                    parseJson(row.getString("extracted")), row.getString("failure_reason"), true, List.of()),
                revision, caseId, revision, revision);
        return new Snapshot(caseId, revision, true, documents);
    }

    /** Staff-only view enriches the current OCR snapshot with deterministic reference comparisons. */
    @Transactional(readOnly = true)
    public Snapshot currentStaffSnapshot(long caseId, int revision) {
        Snapshot current = currentSnapshot(caseId, revision);
        List<OcrDocument> documents = current.documents().stream().map(document -> new OcrDocument(
                document.documentFileId(), document.caseRevision(), document.documentTypeCode(), document.personRef(),
                document.method(), document.status(), document.extracted(), document.failureReason(), true,
                comparison.compare(caseId, revision, document))).toList();
        return new Snapshot(caseId, revision, true, documents);
    }

    /** Backward-compatible payload consumed by the existing Staff Agent snapshot builder. */
    public List<java.util.Map<String, Object>> snapshot(long caseId, int revision) {
        return currentStaffSnapshot(caseId, revision).documents().stream().map(document -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("documentFileId", document.documentFileId());
            item.put("caseRevision", document.caseRevision());
            item.put("documentTypeCode", document.documentTypeCode());
            item.put("personRef", document.personRef());
            item.put("method", document.method());
            item.put("status", document.status());
            item.put("failureReason", document.failureReason());
            item.put("manualVerificationRequired", true);
            item.put("fieldValidations", document.fieldComparisons().stream().map(field -> {
                java.util.Map<String,Object> validation = new java.util.LinkedHashMap<>();
                validation.put("fieldName", field.fieldName()); validation.put("status", field.status());
                validation.put("confidence", field.confidence()); validation.put("manualVerificationRequired", field.manualVerificationRequired());
                validation.put("reasonCode", field.reasonCode()); return validation;
            }).toList());
            return item;
        }).toList();
    }

    private JsonNode parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readTree(value);
        } catch (Exception ignored) {
            // Invalid persisted JSON should not leak raw DB text or block the Case view.
            return null;
        }
    }

    public record Snapshot(long caseId, int revision, boolean manualVerificationRequired, List<OcrDocument> documents) { }
    public record OcrDocument(long documentFileId, int caseRevision, String documentTypeCode, String personRef,
                              String method, String status, JsonNode extracted, String failureReason,
                              boolean manualVerificationRequired,
                              List<C02OcrComparisonService.FieldComparison> fieldComparisons) {
        public OcrDocument(long documentFileId, int caseRevision, String documentTypeCode, String personRef,
                           String method, String status, JsonNode extracted, String failureReason,
                           boolean manualVerificationRequired) {
            this(documentFileId, caseRevision, documentTypeCode, personRef, method, status, extracted,
                    failureReason, manualVerificationRequired, List.of());
        }
    }
}
