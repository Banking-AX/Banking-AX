package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only C-02 Case lists so testers can move between screens without typing Case numbers.
 * Customers only see their own Cases. The staff pre-review inbox scope is still an open team
 * decision (AGENTS.md 5); until then only staff with user.can_pre_review=true see submitted Cases,
 * and DRAFT Cases are never listed to staff.
 */
@RestController
@com.bankingax.core.persistence.ConditionalOnDataSource
public class C02CaseListBffController {
    private static final String COLUMNS = """
        select sc.id, sc.work_item_id, sc.case_status, sc.review_status, sc.revision, sc.created_at,
               sc.submitted_at, sc.decided_at, sc.result_notified_at, u.name
          from service_case sc join `user` u on u.id = sc.customer_id
        """;
    private final JdbcTemplate jdbc;
    private final TaskBffPrincipal principals;

    public C02CaseListBffController(JdbcTemplate jdbc, TaskBffPrincipal principals) {
        this.jdbc = jdbc;
        this.principals = principals;
    }

    @GetMapping("/api/v1/customer/c02/cases")
    @Transactional(readOnly = true)
    public ApiEnvelope<List<CaseRow>> customerCases(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        List<CaseRow> rows = jdbc.query(COLUMNS + " where sc.customer_id=? and sc.case_type='INHERITANCE_DEPOSIT' order by sc.id desc limit 50",
                (rs, n) -> row(rs), customerId);
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, null, null, rows);
    }

    @GetMapping("/api/v1/staff/c02/cases")
    @Transactional(readOnly = true)
    public ApiEnvelope<List<CaseRow>> staffQueue(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        long staffId = principals.staff(authentication);
        Boolean allowed = jdbc.query("select can_pre_review from `user` where id=? and is_active=true",
                rs -> rs.next() ? rs.getBoolean(1) : null, staffId);
        if (!Boolean.TRUE.equals(allowed)) throw new DomainException("FORBIDDEN", "Pre-review inbox access is not granted to this staff account.");
        List<CaseRow> rows = jdbc.query(COLUMNS + " where sc.case_type='INHERITANCE_DEPOSIT' and sc.case_status<>'DRAFT' order by sc.submitted_at desc, sc.id desc limit 100",
                (rs, n) -> row(rs));
        return ApiEnvelope.success(requestId, "INHERITANCE_DEPOSIT", null, null, null, rows);
    }

    private static CaseRow row(ResultSet rs) throws SQLException {
        return new CaseRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)), rs.getString(10));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record CaseRow(long caseId, long workItemId, String caseStatus, String reviewStatus, int revision,
                          Instant createdAt, Instant submittedAt, Instant decidedAt, Instant resultNotifiedAt, String customerName) { }
}
