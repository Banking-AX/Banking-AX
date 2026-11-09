package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.notification.NotificationWriter;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAFF-21: a staff member asks the customer one factual question during the precheck; the customer's own answer is
 * stored as written and the same Case is re-checked on a new revision. Nothing here decides the Case or writes text for
 * the customer. Questions are asked before the review starts, because only then can the Case take a new revision.
 */
@Service
@ConditionalOnDataSource
public class C02ClarificationService {
    static final Duration ANSWER_WINDOW = Duration.ofHours(72);
    private static final Set<String> KINDS = Set.of("VISIT_ATTENDANCE", "DOCUMENT", "OTHER");

    private final JdbcTemplate jdbc;
    private final NotificationWriter notifications;
    private final C02IntakeService intake;
    private final TransactionTemplate tx;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(C02ClarificationService.class);

    public C02ClarificationService(JdbcTemplate jdbc, ObjectProvider<NotificationWriter> notifications, C02IntakeService intake, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.notifications = notifications.getIfAvailable();
        this.intake = intake;
        this.tx = new TransactionTemplate(transactions);
    }

    private record Answered(long clarificationId, boolean recheck) { }

    public record Clarification(long id, long caseId, String kind, String question, String status, Instant askedAt, Instant dueAt,
                                Long askedBy, String askedByName, int requestedRevision, String answer, Instant answeredAt,
                                Integer resultRevision, String nonresponseAssumption) { }
    public record Ask(String kind, String question, String requestId) { }
    public record Answer(String answer, String requestId) { }

    @Transactional
    public Clarification ask(long staffId, long caseId, Ask command) {
        String requestId = requestId(command.requestId());
        String kind = command.kind() == null ? "OTHER" : command.kind();
        if (!KINDS.contains(kind)) throw new DomainException("VALIDATION_ERROR", "kind must be VISIT_ATTENDANCE, DOCUMENT or OTHER.");
        String question = command.question() == null ? "" : command.question().trim();
        if (question.length() < 5 || question.length() > 500) throw new DomainException("VALIDATION_ERROR", "The question needs 5 to 500 characters.");
        List<Map<String, Object>> rows = jdbc.queryForList("select customer_id, work_item_id, case_status, revision from service_case where id=? and case_type='INHERITANCE_DEPOSIT' for update", caseId);
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        // Replay is read after the Case lock, so a concurrent copy of this request sees the committed question.
        Long replay = replayed(requestId, "C02_CLARIFICATION_REQUESTED");
        if (replay != null) return find(replay);
        Map<String, Object> c = rows.get(0);
        if (!"SUBMITTED".equals(c.get("case_status")))
            throw new DomainException("CLARIFICATION_NOT_ALLOWED", "Questions can be sent only before the review starts; after that, record the gap in the staff result.");
        int revision = ((Number) c.get("revision")).intValue();
        Instant due = Instant.now().plus(ANSWER_WINDOW);
        jdbc.update("insert into clarification(case_id,requested_revision,kind,question,status,due_at) values(?,?,?,?, 'OPEN', ?)",
                caseId, revision, kind, question, Timestamp.from(due));
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("update service_case set review_status='WAITING_CUSTOMER', version=version+1 where id=?", caseId);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_CLARIFICATION_REQUESTED',null,'OPEN','STAFF',?,?,JSON_OBJECT('clarificationId',?,'kind',?,'revision',?))",
                String.valueOf(caseId), staffId, requestId, id, kind, revision);
        if (notifications != null) notifications.store(((Number) c.get("customer_id")).longValue(), caseId, ((Number) c.get("work_item_id")).longValue(),
                "C02_CLARIFICATION_REQUESTED", "추가 확인이 필요해요", question, "c02-clarify-" + id);
        return find(id);
    }

    /**
     * Customer's own answer, stored as written. The answer commits first; when it closes the last open question the
     * Case then gets a new revision and the precheck runs again on it, in its own transactions. A failing precheck is
     * recorded as FAILED_NEEDS_STAFF by the intake service and can never undo the customer's answer. A replayed or
     * concurrent copy of the same request returns the stored answer and does not re-check again.
     */
    public Clarification answer(long customerId, long caseId, long clarificationId, Answer command) {
        String requestId = requestId(command.requestId());
        Answered stored = tx.execute(status -> answerInTransaction(customerId, caseId, clarificationId, command, requestId));
        if (stored.recheck()) {
            try {
                int revision = intake.recheckAfterClarification(customerId, caseId, requestId);
                // Every answer gathered for this re-check points at the revision it produced.
                tx.executeWithoutResult(status -> jdbc.update("update clarification set result_revision=? where case_id=? and status='ANSWERED' and result_revision is null", revision, caseId));
            } catch (RuntimeException failure) {
                // The answer is kept; staff can run the precheck again from the Case.
                log.warn("Re-check after a clarification answer did not run for case {}: {}", caseId, failure.getClass().getSimpleName());
            }
        }
        return find(stored.clarificationId());
    }

    private Answered answerInTransaction(long customerId, long caseId, long clarificationId, Answer command, String requestId) {
        String answer = command.answer() == null ? "" : command.answer().trim();
        if (answer.isEmpty() || answer.length() > 1000) throw new DomainException("VALIDATION_ERROR", "The answer needs 1 to 1000 characters.");
        // The Case row lock is the first statement, so every read below (replay included) sees what a concurrent copy
        // of this request committed while we waited. Lock order matches ask(): service_case, then clarification.
        List<Long> owners = jdbc.queryForList("select customer_id from service_case where id=? and case_type='INHERITANCE_DEPOSIT' for update", Long.class, caseId);
        if (owners.isEmpty()) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        if (owners.get(0) != customerId) throw new DomainException("FORBIDDEN", "Case ownership does not match.");
        Long replay = replayed(requestId, "C02_CLARIFICATION_ANSWERED");
        if (replay != null) return new Answered(replay, false);
        expire(caseId);
        List<Map<String, Object>> rows = jdbc.queryForList("select status from clarification where id=? and case_id=? for update", clarificationId, caseId);
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "The question was not found.");
        if (!"OPEN".equals(rows.get(0).get("status"))) throw new DomainException("CLARIFICATION_CLOSED", "This question is no longer open.");
        jdbc.update("update clarification set status='ANSWERED', answer_text=?, confirmed_at=UTC_TIMESTAMP(6) where id=?", answer, clarificationId);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_CLARIFICATION_ANSWERED','OPEN','ANSWERED','CUSTOMER',?,?,JSON_OBJECT('clarificationId',?))",
                String.valueOf(caseId), customerId, requestId, clarificationId);
        Integer stillOpen = jdbc.queryForObject("select count(*) from clarification where case_id=? and status='OPEN'", Integer.class, caseId);
        return new Answered(clarificationId, stillOpen != null && stillOpen == 0);
    }

    @Transactional
    public List<Clarification> listForStaff(long caseId) {
        expire(caseId);
        return list(caseId);
    }

    @Transactional
    public List<Clarification> listForCustomer(long customerId, long caseId) {
        owner(customerId, caseId);
        expire(caseId);
        return list(caseId);
    }

    /**
     * A question past its due time closes as EXPIRED. A visit question keeps only the provisional UNCONFIRMED_ABSENT,
     * never a confirmed absence; the Case goes back to staff.
     */
    private void expire(long caseId) {
        // Lock order for every clarification write: the service_case row first, then its questions (as ask() does).
        jdbc.queryForList("select id from service_case where id=? for update", Long.class, caseId);
        List<Long> due = jdbc.queryForList("select id from clarification where case_id=? and status='OPEN' and due_at < UTC_TIMESTAMP(6) for update", Long.class, caseId);
        for (Long id : due) {
            jdbc.update("update clarification set status='EXPIRED', nonresponse_visit_assumption=case when kind='VISIT_ATTENDANCE' then 'UNCONFIRMED_ABSENT' else null end where id=? and status='OPEN'", id);
            jdbc.update("insert ignore into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,request_id,payload) values('SERVICE_CASE',?,'C02_CLARIFICATION_EXPIRED','OPEN','EXPIRED','SYSTEM',?,JSON_OBJECT('clarificationId',?))",
                    String.valueOf(caseId), "c02-clarify-expired-" + id, id);
        }
        if (!due.isEmpty()) {
            Integer open = jdbc.queryForObject("select count(*) from clarification where case_id=? and status='OPEN'", Integer.class, caseId);
            if (open != null && open == 0) jdbc.update("update service_case set review_status='READY_FOR_STAFF', version=version+1 where id=? and review_status='WAITING_CUSTOMER'", caseId);
        }
    }

    private List<Clarification> list(long caseId) {
        return jdbc.query(SELECT + " where c.case_id=? order by c.id desc", (rs, n) -> map(rs), caseId);
    }

    private Clarification find(long id) {
        return jdbc.query(SELECT + " where c.id=?", (rs, n) -> map(rs), id).stream().findFirst().orElseThrow(() -> new DomainException("NOT_FOUND", "The question was not found."));
    }

    private static final String SELECT = """
        select c.id, c.case_id, c.kind, c.question, c.status, c.created_at, c.due_at, e.actor_id, u.name, c.requested_revision,
               c.answer_text, c.confirmed_at, c.result_revision, c.nonresponse_visit_assumption
          from clarification c
          left join event_log e on e.event_type='C02_CLARIFICATION_REQUESTED' and json_extract(e.payload,'$.clarificationId') = c.id
          left join `user` u on u.id = e.actor_id
        """;

    private static Clarification map(ResultSet rs) throws SQLException {
        return new Clarification(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)),
                (Long) rs.getObject(8, Long.class), rs.getString(9), rs.getInt(10), rs.getString(11), instant(rs.getTimestamp(12)),
                (Integer) rs.getObject(13, Integer.class), rs.getString(14));
    }

    private void owner(long customerId, long caseId) {
        List<Long> owners = jdbc.queryForList("select customer_id from service_case where id=? and case_type='INHERITANCE_DEPOSIT'", Long.class, caseId);
        if (owners.isEmpty()) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        if (owners.get(0) != customerId) throw new DomainException("FORBIDDEN", "Case ownership does not match.");
    }

    private Long replayed(String requestId, String eventType) {
        List<Map<String, Object>> rows = jdbc.queryForList("select event_type, json_extract(payload,'$.clarificationId') as id from event_log where request_id=?", requestId);
        if (rows.isEmpty()) return null;
        if (!eventType.equals(rows.get(0).get("event_type"))) throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another action.");
        return Long.valueOf(String.valueOf(rows.get(0).get("id")));
    }

    private static String requestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) throw new DomainException("VALIDATION_ERROR", "requestId is required (max 90).");
        return requestId;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
