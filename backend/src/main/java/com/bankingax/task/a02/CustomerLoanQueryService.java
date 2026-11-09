package com.bankingax.task.a02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection for the customer loan detail page; it does not evaluate B-01 eligibility. */
@Service
@ConditionalOnDataSource
public class CustomerLoanQueryService {
    private static final String B01_TASK_CODE = "REPAYMENT_ORDER_CHANGE";

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerLoanQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CustomerLoanList findOwnedLoans(long customerId) {
        Map<String, Object> parameters = Map.of(
                "customerId", customerId,
                "b01TaskCode", B01_TASK_CODE);
        LocalDateTime databaseNow = jdbc.queryForObject(
                "select UTC_TIMESTAMP(6)", Map.of(), LocalDateTime.class);
        List<CustomerLoan> loans = jdbc.query("""
                select l.id,l.loan_no,l.loan_name,l.balance,l.interest_rate,l.overdue_days,
                       l.acceleration_declared,l.acceleration_date,l.repayment_order,
                       completed_b01.changed_at
                  from loan l
                  left join (
                        select tr.target_loan_id,max(tr.created_at) changed_at
                          from task_result tr
                          join work_item wi on wi.id=tr.work_item_id
                          join workflow wf on wf.id=wi.workflow_id
                         where tr.result_type=:b01TaskCode
                           and wi.task_code=:b01TaskCode
                           and wi.status='DONE'
                           and wf.customer_id=:customerId
                         group by tr.target_loan_id
                  ) completed_b01 on completed_b01.target_loan_id=l.id
                 where l.customer_id=:customerId
                 order by l.id asc
                """, parameters, (result, row) -> {
            String repaymentOrder = result.getString("repayment_order");
            return new CustomerLoan(
                    result.getLong("id"),
                    JdbcLoanQueryPort.mask(result.getString("loan_no")),
                    result.getString("loan_name"),
                    result.getBigDecimal("balance"),
                    result.getBigDecimal("interest_rate"),
                    result.getInt("overdue_days"),
                    result.getBoolean("acceleration_declared"),
                    result.getObject("acceleration_date", LocalDate.class),
                    repaymentOrder,
                    repaymentOrderSteps(repaymentOrder),
                    utc(result.getObject("changed_at", LocalDateTime.class)));
        });
        return new CustomerLoanList(utc(databaseNow), loans);
    }

    private static List<String> repaymentOrderSteps(String repaymentOrder) {
        return switch (repaymentOrder) {
            case "INTEREST_FIRST" -> List.of("COST", "INTEREST", "PRINCIPAL");
            case "PRINCIPAL_FIRST" -> List.of("COST", "PRINCIPAL", "INTEREST");
            default -> throw new DomainException("LOAN_DATA_INVALID", "The repayment order is invalid.");
        };
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record CustomerLoanList(Instant asOf, List<CustomerLoan> loans) { }

    public record CustomerLoan(long loanId, String loanNoMasked, String loanName, BigDecimal balance,
                               BigDecimal interestRate, int overdueDays, boolean accelerationDeclared,
                               LocalDate accelerationDate, String repaymentOrder,
                               List<String> repaymentOrderSteps, Instant repaymentOrderChangedAt) { }
}
