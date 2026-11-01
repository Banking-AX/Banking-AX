package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The DB transaction for the human C-02 outcome.  It intentionally does not
 * make an entitlement decision: it records the staff supplied mock-review
 * outcome, advances the Case, and creates the in-service notification.
 */
@Service
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "app.c02", name = "review-persistence-enabled", havingValue = "true")
public class C02StaffDecisionPersistence {
    private final JdbcTemplate jdbc;

    public C02StaffDecisionPersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public StoredDecision persist(long staffId, long caseId, int revision, String requestId,
                                  String outcome, String message, String targetStatus) {
        StoredDecision replay = findByRequestId(requestId);
        if (replay != null) {
            if (replay.caseId != caseId || replay.staffId != staffId || replay.revision != revision
                    || !replay.outcome.equals(outcome) || !replay.message.equals(message)) {
                throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another C-02 staff decision.");
            }
            return replay;
        }

        CaseRow c = jdbc.query("select id, customer_id, work_item_id, revision, case_status, review_status from service_case where id=? for update",
            rs -> rs.next() ? new CaseRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("work_item_id"),
                rs.getInt("revision"), rs.getString("case_status"), rs.getString("review_status")) : null, caseId);
        if (c == null) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        if (c.revision != revision) throw stale();
        if (!"SUBMITTED".equals(c.caseStatus)) throw new DomainException("INVALID_STATE", "Only a submitted C-02 case can receive a staff decision.");
        if (!List.of("READY_FOR_STAFF", "WAITING_CUSTOMER", "FAILED_NEEDS_STAFF").contains(c.reviewStatus)) {
            throw new DomainException("STAFF_PRECHECK_REQUIRED", "A current C-02 staff precheck is required before final review.");
        }
        Integer finalCount = jdbc.queryForObject("select count(*) from staff_review where case_id=? and decided_at is not null", Integer.class, caseId);
        if (finalCount != null && finalCount > 0) throw new DomainException("STAFF_DECISION_ALREADY_RECORDED", "A final staff decision is already recorded for this C-02 case.");

        jdbc.update("insert into staff_review(case_id,staff_id,revision,started_at,decided_at,result,opinion_final) values(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)",
            caseId, staffId, revision, outcome, message);
        jdbc.update("update service_case set case_status=?, decided_at=UTC_TIMESTAMP(6), result_notified_at=UTC_TIMESTAMP(6), version=version+1 where id=? and revision=? and case_status='SUBMITTED'",
            targetStatus, caseId, revision);
        String dedupKey = "c02-web-" + caseId + "-" + revision;
        jdbc.update("insert into notification(customer_id,case_id,work_item_id,type,title,content,dedup_key) values(?,?,?,?,?,?,?)",
            c.customerId, caseId, c.workItemId, "C02_STAFF_REVIEW_" + outcome, "C-02 staff review result", message, dedupKey);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_STAFF_DECIDED','SUBMITTED',?,'STAFF',?,?,JSON_OBJECT('revision',?,'outcome',?,'message',?))",
            String.valueOf(caseId), targetStatus, staffId, requestId, revision, outcome, message);
        return new StoredDecision(caseId, c.customerId, c.workItemId, staffId, revision, outcome, message, targetStatus, dedupKey);
    }

    @Transactional(readOnly = true)
    public List<StoredNotification> notifications(long customerId) {
        return jdbc.query("select id,case_id,content,dedup_key,type from notification where customer_id=? and case_id is not null order by created_at,id",
            (rs, ignored) -> new StoredNotification(rs.getLong("id"), rs.getLong("case_id"), rs.getString("type"), rs.getString("content"), rs.getString("dedup_key")), customerId);
    }

    @Transactional(readOnly = true)
    public Long customerId(long caseId) {
        return jdbc.query("select customer_id from service_case where id=?", rs -> rs.next() ? rs.getLong(1) : null, caseId);
    }

    @Transactional(readOnly = true)
    public StoredDecision replay(String requestId) { return findByRequestId(requestId); }

    @Transactional(readOnly = true)
    public StoredDecision finalDecision(long caseId) {
        return jdbc.query("""
            select sr.case_id, sc.customer_id, sc.work_item_id, sr.staff_id, sr.revision,
                   sr.result, sr.opinion_final, sc.case_status
              from staff_review sr join service_case sc on sc.id=sr.case_id
             where sr.case_id=? and sr.decided_at is not null
            """, rs -> rs.next() ? new StoredDecision(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8),
                "c02-web-" + rs.getLong(1) + "-" + rs.getInt(5)) : null, caseId);
    }

    @Transactional(readOnly = true)
    public boolean hasCurrentPrecheck(long caseId, int revision) {
        Integer count = jdbc.queryForObject("select count(*) from service_case where id=? and revision=? and review_status in ('READY_FOR_STAFF','WAITING_CUSTOMER','FAILED_NEEDS_STAFF')", Integer.class, caseId, revision);
        return count != null && count == 1;
    }

    private StoredDecision findByRequestId(String requestId) {
        return jdbc.query("""
            select e.entity_id, JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.revision')),
                   JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.outcome')),
                   JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.message')), e.actor_id,
                   sc.customer_id, sc.work_item_id, e.to_value
              from event_log e join service_case sc on sc.id=cast(e.entity_id as unsigned)
             where e.request_id=? and e.event_type='C02_STAFF_DECIDED'
            """, rs -> rs.next() ? new StoredDecision(Long.parseLong(rs.getString(1)), rs.getLong(6), rs.getLong(7),
                rs.getLong(5), Integer.parseInt(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(8),
                "c02-web-" + rs.getString(1) + "-" + rs.getString(2)) : null, requestId);
    }

    private static DomainException stale() { return new DomainException("VERSION_CONFLICT", "Case revision is stale.", true); }

    public record StoredDecision(long caseId, long customerId, long workItemId, long staffId, int revision,
                                 String outcome, String message, String targetStatus, String notificationId) { }
    public record StoredNotification(long id, long caseId, String type, String content, String dedupKey) { }
    private record CaseRow(long id, long customerId, long workItemId, int revision, String caseStatus, String reviewStatus) { }
}
