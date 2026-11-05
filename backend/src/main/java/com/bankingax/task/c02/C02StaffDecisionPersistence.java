package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The DB transaction for the human C-02 outcome.  It intentionally does not
 * make an entitlement decision: it records the staff supplied mock-review
 * outcome, advances the Case, and creates the in-service notification.
 */
@Service
@com.bankingax.core.persistence.ConditionalOnDataSource
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
        if (!"SUBMITTED".equals(c.caseStatus) && !"IN_REVIEW".equals(c.caseStatus)) throw new DomainException("INVALID_STATE", "Only a submitted C-02 case can receive a staff decision.");
        if (!List.of("READY_FOR_STAFF", "WAITING_CUSTOMER", "FAILED_NEEDS_STAFF").contains(c.reviewStatus)) {
            throw new DomainException("STAFF_PRECHECK_REQUIRED", "A current C-02 staff precheck is required before final review.");
        }
        Integer finalCount = jdbc.queryForObject("select count(*) from staff_review where case_id=? and decided_at is not null", Integer.class, caseId);
        if (finalCount != null && finalCount > 0) throw new DomainException("STAFF_DECISION_ALREADY_RECORDED", "A final staff decision is already recorded for this C-02 case.");

        // A review opened by startReview is closed by the deciding staff member; otherwise one row records both.
        int closed = jdbc.update("update staff_review set staff_id=?, decided_at=UTC_TIMESTAMP(6), result=?, opinion_final=?, version=version+1 where case_id=? and revision=? and decided_at is null order by id desc limit 1",
            staffId, outcome, message, caseId, revision);
        if (closed == 0) jdbc.update("insert into staff_review(case_id,staff_id,revision,started_at,decided_at,result,opinion_final) values(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)",
            caseId, staffId, revision, outcome, message);
        jdbc.update("update service_case set case_status=?, decided_at=UTC_TIMESTAMP(6), result_notified_at=UTC_TIMESTAMP(6), version=version+1 where id=? and revision=? and case_status in ('SUBMITTED','IN_REVIEW')",
            targetStatus, caseId, revision);
        String dedupKey = "c02-web-" + caseId + "-" + revision;
        jdbc.update("insert into notification(customer_id,case_id,work_item_id,type,title,content,dedup_key) values(?,?,?,?,?,?,?)",
            c.customerId, caseId, c.workItemId, notificationType(outcome), notificationTitle(outcome),
            message == null || message.isBlank() ? DEFAULT_RESULT_CONTENT : message, dedupKey);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_STAFF_DECIDED',?,?,'STAFF',?,?,JSON_OBJECT('revision',?,'outcome',?,'message',?))",
            String.valueOf(caseId), c.caseStatus, targetStatus, staffId, requestId, revision, outcome, message);
        return new StoredDecision(caseId, c.customerId, c.workItemId, staffId, revision, outcome, message, targetStatus, dedupKey);
    }

    /** Spec 11-3 type codes for the staff outcome notification. */
    static String notificationType(String outcome) {
        return switch (outcome) {
            case "PROCEED" -> "CASE_APPROVED";
            case "REQUIREMENT_FAILED" -> "CASE_REJECTED_REQUIREMENT";
            case "DOCUMENT_INSUFFICIENT" -> "CASE_REJECTED_DOCUMENT";
            default -> throw new IllegalArgumentException("Unknown C-02 staff outcome: " + outcome);
        };
    }

    static String notificationTitle(String outcome) {
        return switch (outcome) {
            case "PROCEED" -> "상속예금 사전검토 결과: 진행 가능";
            case "REQUIREMENT_FAILED" -> "상속예금 사전검토 결과: 요건 미충족";
            case "DOCUMENT_INSUFFICIENT" -> "상속예금 사전검토 결과: 서류 부족";
            default -> throw new IllegalArgumentException("Unknown C-02 staff outcome: " + outcome);
        };
    }

    /** Reverses notificationType; rows written before the spec codes used the C02_STAFF_REVIEW_ prefix. */
    static String outcomeOf(String type) {
        if (type == null) return null;
        return switch (type) {
            case "CASE_APPROVED" -> "PROCEED";
            case "CASE_REJECTED_REQUIREMENT" -> "REQUIREMENT_FAILED";
            case "CASE_REJECTED_DOCUMENT" -> "DOCUMENT_INSUFFICIENT";
            default -> type.startsWith("C02_STAFF_REVIEW_") ? type.substring("C02_STAFF_REVIEW_".length()) : type;
        };
    }

    static final String DEFAULT_RESULT_CONTENT = "직원 검토 결과를 확인해 주세요. 이 알림은 모의 접수 검토 결과이며 법적 통지가 아니에요.";

    /** Only staff outcome rows (dedup key c02-web-{case}-{revision}); other C-02 notifications are listed by the shared notification API. */
    @Transactional(readOnly = true)
    public List<StoredNotification> notifications(long customerId) {
        return jdbc.query("select id,case_id,content,dedup_key,type from notification where customer_id=? and case_id is not null and dedup_key like 'c02-web-%' order by created_at,id",
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

    /**
     * Staff "검토 시작". The conditional UPDATE locks the Case row, so it races safely with
     * {@link #cancelByCustomer}: whichever commits first wins and the other sees 0 rows (T-02/T-03).
     */
    @Transactional
    public Lifecycle startReview(long staffId, long caseId, int revision, String requestId) {
        if (replayed(requestId)) return lifecycle(caseId);
        int updated = jdbc.update("""
            update service_case set case_status='IN_REVIEW', review_started_at=UTC_TIMESTAMP(6), version=version+1
             where id=? and revision=? and case_type='INHERITANCE_DEPOSIT' and case_status='SUBMITTED'
               and review_status in ('READY_FOR_STAFF','WAITING_CUSTOMER','FAILED_NEEDS_STAFF')
            """, caseId, revision);
        if (updated == 0) {
            Lifecycle current = lifecycle(caseId);
            if (current == null) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
            if (current.revision() != revision) throw stale();
            if ("SUBMITTED".equals(current.caseStatus())) throw new DomainException("STAFF_PRECHECK_REQUIRED", "A current C-02 staff precheck is required before final review.");
            throw new DomainException("INVALID_STATE", "Only a submitted C-02 case can start staff review.");
        }
        jdbc.update("insert into staff_review(case_id,staff_id,revision,started_at) values(?,?,?,UTC_TIMESTAMP(6))", caseId, staffId, revision);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_REVIEW_STARTED','SUBMITTED','IN_REVIEW','STAFF',?,?,JSON_OBJECT('revision',?))",
            String.valueOf(caseId), staffId, requestId, revision);
        return lifecycle(caseId);
    }

    /** Customer cancellation is allowed only before staff review starts (3-2). */
    @Transactional
    public Lifecycle cancelByCustomer(long customerId, long caseId, String requestId) {
        if (replayed(requestId)) return lifecycle(caseId);
        int updated = jdbc.update("update service_case set case_status='CANCELED', canceled_at=UTC_TIMESTAMP(6), version=version+1 where id=? and customer_id=? and case_type='INHERITANCE_DEPOSIT' and case_status='SUBMITTED'",
            caseId, customerId);
        if (updated == 0) {
            String status = jdbc.query("select case_status from service_case where id=? and customer_id=?", rs -> rs.next() ? rs.getString(1) : null, caseId, customerId);
            if (status == null) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
            throw new DomainException("CASE_CANCEL_NOT_ALLOWED", "The case can only be canceled before staff review starts.");
        }
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_CANCELED_BY_CUSTOMER','SUBMITTED','CANCELED','CUSTOMER',?,?,null)",
            String.valueOf(caseId), customerId, requestId);
        return lifecycle(caseId);
    }

    private boolean replayed(String requestId) {
        Integer count = jdbc.queryForObject("select count(*) from event_log where request_id=?", Integer.class, requestId);
        return count != null && count > 0;
    }

    private Lifecycle lifecycle(long caseId) {
        return jdbc.query("select id, revision, case_status from service_case where id=?",
            rs -> rs.next() ? new Lifecycle(rs.getLong(1), rs.getInt(2), rs.getString(3)) : null, caseId);
    }

    /**
     * CasePort.submit does not record submitted_at yet (core owner fix pending). Fill it only when empty,
     * so a later CasePort fix keeps its own value.
     */
    @Transactional
    public void markSubmittedAt(long caseId) {
        jdbc.update("update service_case set submitted_at=coalesce(submitted_at, UTC_TIMESTAMP(6)) where id=? and case_status='SUBMITTED'", caseId);
    }

    /** Customer-visible milestones of one Case; null when the row does not exist. */
    @Transactional(readOnly = true)
    public Timeline timeline(long caseId) {
        return jdbc.query("""
            select created_at, submitted_at, review_started_at, decided_at, result_notified_at, canceled_at, updated_at
              from service_case where id=?
            """, rs -> rs.next() ? new Timeline(instant(rs, 1), instant(rs, 2), instant(rs, 3), instant(rs, 4),
                instant(rs, 5), instant(rs, 6), instant(rs, 7)) : null, caseId);
    }

    private static java.time.Instant instant(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
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
    public record Lifecycle(long caseId, int revision, String caseStatus) { }
    public record Timeline(java.time.Instant createdAt, java.time.Instant submittedAt, java.time.Instant reviewStartedAt,
                           java.time.Instant decidedAt, java.time.Instant resultNotifiedAt, java.time.Instant canceledAt,
                           java.time.Instant updatedAt) { }
    private record CaseRow(long id, long customerId, long workItemId, int revision, String caseStatus, String reviewStatus) { }
}
