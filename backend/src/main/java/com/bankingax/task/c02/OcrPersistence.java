package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

    public OcrPersistence(JdbcTemplate jdbc, OcrPort ocr, ObjectMapper json) {
        this.jdbc = jdbc;
        this.ocr = ocr;
        this.json = json;
    }

    @Transactional
    public void extract(long fileId, long caseId, int revision, String type, String storage) {
        try {
            var result = ocr.extract(new OcrPort.ExtractRequest(fileId, type, storage));
            jdbc.update("insert into document_extraction(document_file_id,case_id,revision,method,status,extracted,failure_reason) values(?,?,?,?,?,cast(? as json),?)",
                    fileId, caseId, revision, result.method(), result.status(), json.writeValueAsString(result.fields()), result.failureReason());
        } catch (Exception exception) {
            // OCR is auxiliary. A failed extraction remains a manual-review item and must not undo upload.
            jdbc.update("insert into document_extraction(document_file_id,case_id,revision,method,status,failure_reason) values(?,?,?,'NONE','FAILED_NEEDS_STAFF',?)",
                    fileId, caseId, revision, "OCR_UNAVAILABLE");
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
                    parseJson(row.getString("extracted")), row.getString("failure_reason"), true),
                revision, caseId, revision, revision);
        return new Snapshot(caseId, revision, true, documents);
    }

    /** Backward-compatible payload consumed by the existing Staff Agent snapshot builder. */
    public List<java.util.Map<String, Object>> snapshot(long caseId, int revision) {
        return currentSnapshot(caseId, revision).documents().stream().map(document -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("documentFileId", document.documentFileId());
            item.put("caseRevision", document.caseRevision());
            item.put("documentTypeCode", document.documentTypeCode());
            item.put("personRef", document.personRef());
            item.put("method", document.method());
            item.put("status", document.status());
            item.put("extracted", document.extracted());
            item.put("failureReason", document.failureReason());
            item.put("manualVerificationRequired", true);
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
                              boolean manualVerificationRequired) { }
}
