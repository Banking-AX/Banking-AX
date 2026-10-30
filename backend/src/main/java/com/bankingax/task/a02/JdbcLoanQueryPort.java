package com.bankingax.task.a02;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only MySQL adapter. Ownership is enforced in every loan query. */
@Component
@ConditionalOnProperty(name = "app.a02.loan-query-port", havingValue = "jdbc")
public class JdbcLoanQueryPort implements LoanQueryPort {
    private static final String SELECT = """
            SELECT id, loan_no, loan_name, balance, interest_rate, overdue_days,
                   acceleration_declared, repayment_order
              FROM loan
             WHERE customer_id = :customerId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLoanQueryPort(NamedParameterJdbcTemplate jdbc,
                             @Value("${app.case-port:inmemory}") String casePort) {
        if (!"jpa".equalsIgnoreCase(casePort)) {
            throw new IllegalStateException(
                    "APP_A02_LOAN_QUERY_PORT=jdbc requires APP_CASE_PORT=jpa for WorkItem validation.");
        }
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanSummary> findOwnedLoans(long customerId, Long loanId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("customerId", customerId);
        StringBuilder sql = new StringBuilder(SELECT);
        if (loanId != null) {
            sql.append(" AND id = :loanId");
            parameters.put("loanId", loanId);
        }
        sql.append(" ORDER BY id ASC");
        return jdbc.query(sql.toString(), parameters, (result, row) -> new LoanSummary(
                result.getLong("id"),
                mask(result.getString("loan_no")),
                result.getString("loan_name"),
                result.getBigDecimal("balance"),
                result.getBigDecimal("interest_rate"),
                result.getInt("overdue_days"),
                result.getBoolean("acceleration_declared"),
                result.getString("repayment_order")));
    }

    static String mask(String loanNo) {
        if (loanNo == null || loanNo.isBlank()) return "***";
        String[] groups = loanNo.split("-", -1);
        if (groups.length >= 3 && !groups[0].isBlank() && !groups[groups.length - 1].isBlank()) {
            return groups[0] + "-***-" + groups[groups.length - 1];
        }
        if (loanNo.length() <= 4) return "***";
        int prefixLength = Math.min(3, loanNo.length() - 4);
        return loanNo.substring(0, prefixLength) + "-***-" + loanNo.substring(loanNo.length() - 4);
    }
}
