package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HIST-11: what the customer wrote about the Case in their own words, kept 30 days (T-14) and shown to staff as written.
 * Only text the customer typed into the C-02 form is stored; chats are never turned into statements and nothing is
 * generated. The text is not copied into case_revision, event_log or the LLM input.
 */
@Service
@ConditionalOnDataSource
public class C02StatementService {
    static final Duration RETENTION = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(C02StatementService.class);
    private final JdbcTemplate jdbc;

    public C02StatementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Statement(long id, long caseId, int revision, String text, Instant writtenAt, Instant expiresAt) { }
    public record Write(String text, String requestId) { }

    @Transactional
    public Statement write(long customerId, long caseId, Write command) {
        String text = command.text() == null ? "" : command.text().trim();
        if (text.isEmpty() || text.length() > 1000) throw new DomainException("VALIDATION_ERROR", "The statement needs 1 to 1000 characters.");
        String requestId = command.requestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) throw new DomainException("VALIDATION_ERROR", "requestId is required (max 90).");
        List<Map<String, Object>> replay = jdbc.queryForList("select entity_id from event_log where request_id=?", requestId);
        if (!replay.isEmpty()) return find(Long.parseLong(String.valueOf(replay.get(0).get("entity_id"))));
        List<Map<String, Object>> rows = jdbc.queryForList("select customer_id, case_status, revision from service_case where id=? and case_type='INHERITANCE_DEPOSIT' for update", caseId);
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        if (((Number) rows.get(0).get("customer_id")).longValue() != customerId) throw new DomainException("FORBIDDEN", "Case ownership does not match.");
        if (!List.of("DRAFT", "SUBMITTED").contains(rows.get(0).get("case_status")))
            throw new DomainException("INVALID_STATE", "A statement can be added until the review starts.");
        int revision = ((Number) rows.get(0).get("revision")).intValue();
        jdbc.update("insert into case_statement(case_id,revision,source_type,summary_text,confirmed_by_customer,source_at,expires_at) values(?,?,'FREE_TEXT',?,true,UTC_TIMESTAMP(6),?)",
                caseId, revision, text, Timestamp.from(Instant.now().plus(RETENTION)));
        long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('CASE_STATEMENT',?,'C02_STATEMENT_WRITTEN',null,'FREE_TEXT','CUSTOMER',?,?,JSON_OBJECT('caseId',?,'revision',?))",
                String.valueOf(id), customerId, requestId, caseId, revision);
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<Statement> listForCustomer(long customerId, long caseId) {
        List<Long> owners = jdbc.queryForList("select customer_id from service_case where id=?", Long.class, caseId);
        if (owners.isEmpty()) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        if (owners.get(0) != customerId) throw new DomainException("FORBIDDEN", "Case ownership does not match.");
        return list(caseId);
    }

    @Transactional(readOnly = true)
    public List<Statement> listForStaff(long caseId) { return list(caseId); }

    @Transactional(readOnly = true)
    public int count(long caseId) {
        Integer n = jdbc.queryForObject("select count(*) from case_statement where case_id=? and expires_at > UTC_TIMESTAMP(6)", Integer.class, caseId);
        return n == null ? 0 : n;
    }

    private List<Statement> list(long caseId) {
        return jdbc.query("select id, case_id, revision, summary_text, captured_at, expires_at from case_statement where case_id=? and expires_at > UTC_TIMESTAMP(6) order by id",
                (rs, n) -> new Statement(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()), caseId);
    }

    private Statement find(long id) {
        return jdbc.query("select id, case_id, revision, summary_text, captured_at, expires_at from case_statement where id=?",
                (rs, n) -> new Statement(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()), id)
                .stream().findFirst().orElseThrow(() -> new DomainException("NOT_FOUND", "Statement not found."));
    }

    /** T-14: expired statement text is deleted outright, not archived. */
    @Transactional
    public int deleteExpired() {
        return jdbc.update("delete from case_statement where expires_at <= UTC_TIMESTAMP(6)");
    }

    @Scheduled(initialDelayString = "${app.jobs.initial-delay-ms:300000}", fixedDelayString = "${app.jobs.statement-retention-ms:3600000}")
    public void scheduledDeletion() {
        int deleted = deleteExpired();
        if (deleted > 0) log.info("Deleted {} expired customer statement(s).", deleted);
    }
}
