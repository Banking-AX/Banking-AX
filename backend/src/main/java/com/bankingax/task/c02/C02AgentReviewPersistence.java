package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists a validated C-02 staff precheck.  The Agent never writes these tables directly. */
@Service
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "app.c02", name = "review-persistence-enabled", havingValue = "true")
public class C02AgentReviewPersistence {
    private static final String CHECK_TYPE = "STAFF_PRECHECK";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public C02AgentReviewPersistence(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public StoredReview persist(String requestId, long caseId, int revision, StaffAgentPort.StaffAgentResponse response) {
        requireCurrentC02Revision(caseId, revision);
        ExistingRun existing = jdbc.query("select id, JSON_UNQUOTE(JSON_EXTRACT(report, '$.requestId')) from agent_review_run where case_id=? and revision=? and check_type=?", rs -> rs.next() ? new ExistingRun(rs.getLong(1), rs.getString(2)) : null, caseId, revision, CHECK_TYPE);
        if (existing != null) {
            if (!requestId.equals(existing.requestId())) throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "This case revision already has a Staff precheck for another request.", true);
            return read(existing.id());
        }
        if (!"SUCCEEDED".equals(response.status())) throw new DomainException("STAFF_AGENT_INVALID_RESPONSE", "Only successful Agent responses may be persisted.", true);
        validateReviewStatus(response.reviewStatus());
        List<Map<String,Object>> items = response.reviewItems() == null ? List.of() : response.reviewItems();
        for (Map<String,Object> item : items) validateItem(caseId, revision, item);
        jdbc.update("insert into agent_review_run(case_id,revision,check_type,status,attempt_count,rule_version,report,started_at,finished_at) values(?,?,?,'SUCCEEDED',1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
            caseId, revision, CHECK_TYPE, C02IntakeService.RULE_VERSION, asJson(Map.of("requestId", requestId, "reviewStatus", response.reviewStatus(), "error", response.error() == null ? Map.of() : response.error())));
        long runId = jdbc.queryForObject("select last_insert_id()", Long.class);
        for (Map<String,Object> item : items) {
            jdbc.update("insert into review_item(case_id,revision,agent_review_run_id,source,category,result,basis_doc_id,document_file_id,heir_id,message,evidence) values(?,?,?,?,?,?,?,?,?,?,?)",
                caseId, revision, runId, text(item,"source","SPRING_RULE"), required(item,"category"), required(item,"result"), nullableText(item,"basisDocId"), nullableLong(item,"documentFileId"), nullableLong(item,"heirId"), nullableText(item,"message"), asJson(item.getOrDefault("evidence", Map.of())));
        }
        return read(runId);
    }

    @Transactional(readOnly = true)
    public StoredReview find(long caseId, int revision) {
        Long id = jdbc.query("select id from agent_review_run where case_id=? and revision=? and check_type=?", rs -> rs.next() ? rs.getLong(1) : null, caseId, revision, CHECK_TYPE);
        return id == null ? null : read(id);
    }

    private void requireCurrentC02Revision(long caseId, int revision) {
        Integer count = jdbc.queryForObject("select count(*) from service_case sc join work_item wi on wi.id=sc.work_item_id join case_revision cr on cr.case_id=sc.id and cr.revision=? where sc.id=? and sc.case_type='INHERITANCE_DEPOSIT' and wi.task_code='INHERITANCE_DEPOSIT' and sc.revision=?", Integer.class, revision, caseId, revision);
        if (count == null || count != 1) throw new DomainException("STAFF_REVIEW_STALE_OR_INVALID_CASE", "Case, work item, or revision is not valid for this Staff precheck.", true);
    }

    private void validateItem(long caseId, int revision, Map<String,Object> item) {
        // Categories are intentionally open-ended: the declared Staff graph can
        // emit deterministic intake, scope, document and semantic hand-off
        // items.  Spring validates their source and every referenced Case/file/
        // heir instead of silently dropping a newer declared category.
        required(item, "category");
        String source = text(item, "source", "SPRING_RULE");
        if (!List.of("SPRING_RULE", "AGENT_SEMANTIC", "PRE_SUBMISSION").contains(source)) throw new DomainException("STAFF_AGENT_INVALID_RESPONSE", "Unsupported review source.", true);
        Long fileId = nullableLong(item, "documentFileId"); Long heirId = nullableLong(item, "heirId");
        String scope = evidenceText(item, "scope"); String personRef = evidenceText(item, "personRef");
        if (fileId != null) {
            Integer count = jdbc.queryForObject("select count(*) from document_file f join required_document rd on rd.id=f.required_document_id join work_item wi on wi.id=rd.work_item_id join service_case sc on sc.work_item_id=wi.id where f.id=? and f.is_latest=true and f.deleted_at is null and sc.id=? and f.case_revision=?", Integer.class, fileId, caseId, revision);
            if (count == null || count != 1) throw new DomainException("STAFF_AGENT_DOCUMENT_MISMATCH", "Agent referenced a stale or foreign document.", true);
            String claimedVersion = evidenceText(item, "documentVersion");
            if (claimedVersion != null && !claimedVersion.equals(String.valueOf(jdbc.queryForObject("select version from document_file where id=?", Integer.class, fileId)))) throw new DomainException("STAFF_AGENT_DOCUMENT_MISMATCH", "Agent document version does not match the persisted document.", true);
        }
        if (heirId != null) {
            Integer count = jdbc.queryForObject("select count(*) from heir where id=? and case_id=?", Integer.class, heirId, caseId);
            if (count == null || count != 1) throw new DomainException("STAFF_AGENT_DOCUMENT_MISMATCH", "Agent referenced a foreign heir.", true);
        }
        if (personRef != null && heirId != null) {
            String actual = jdbc.queryForObject("select person_ref from heir where id=?", String.class, heirId);
            if (!personRef.equals(actual)) throw new DomainException("STAFF_AGENT_DOCUMENT_MISMATCH", "Agent personRef does not match the persisted heir.", true);
        }
        if (scope != null && !List.of("COMMON", "HEIR").contains(scope)) throw new DomainException("STAFF_AGENT_INVALID_RESPONSE", "Invalid document scope.", true);
    }

    private StoredReview read(long runId) {
        return jdbc.queryForObject("select case_id,revision,status from agent_review_run where id=?", (rs, n) -> new StoredReview(runId, rs.getLong(1), rs.getInt(2), rs.getString(3), jdbc.query("select source,category,result,basis_doc_id,document_file_id,heir_id,message from review_item where agent_review_run_id=? order by id", (i, x) -> new StoredItem(i.getString(1),i.getString(2),i.getString(3),i.getString(4),number(i.getObject(5)),number(i.getObject(6)),i.getString(7)), runId)), runId);
    }
    private static void validateReviewStatus(String status) { if (!List.of("READY_FOR_STAFF", "WAITING_CUSTOMER", "FAILED_NEEDS_STAFF").contains(status)) throw new DomainException("STAFF_AGENT_INVALID_RESPONSE", "Invalid Staff review status.", true); }
    private static String required(Map<String,Object> item, String name) { String value = nullableText(item,name); if (value == null || value.isBlank()) throw new DomainException("STAFF_AGENT_INVALID_RESPONSE", "Missing review field: " + name, true); return value; }
    private static String text(Map<String,Object> item,String name,String fallback) { String value=nullableText(item,name); return value == null ? fallback : value; }
    private static String nullableText(Map<String,Object> item,String name) { Object value=item.get(name); return value == null ? null : String.valueOf(value); }
    private static Long nullableLong(Map<String,Object> item,String name) { Object value=item.get(name); return value instanceof Number n ? n.longValue() : value == null ? null : Long.valueOf(String.valueOf(value)); }
    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
    @SuppressWarnings("unchecked") private static String evidenceText(Map<String,Object> item,String name) { Object evidence=item.get("evidence"); return evidence instanceof Map<?,?> map && map.get(name)!=null ? String.valueOf(map.get(name)) : null; }
    private String asJson(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new DomainException("STAFF_AGENT_INVALID_RESPONSE", "Review evidence is not serializable.", true); } }
    public record StoredReview(long runId,long caseId,int revision,String status,List<StoredItem> items) {}
    public record StoredItem(String source,String category,String result,String basisDocId,Long documentFileId,Long heirId,String message) {}
    private record ExistingRun(long id,String requestId) {}
}
