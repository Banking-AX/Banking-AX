package com.bankingax.task.b01;

import java.util.Optional;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.case-port", havingValue = "jpa")
class JdbcB01ValidationQueryPort implements B01ValidationQueryPort {
    private final JdbcTemplate jdbc;

    JdbcB01ValidationQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> ownedWorkItemStatus(long customerId, long workItemId) {
        return jdbc.query("""
                select wi.status
                  from work_item wi join workflow wf on wf.id=wi.workflow_id
                 where wi.id=? and wf.customer_id=? and wi.task_code='REPAYMENT_ORDER_CHANGE'
                """, rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), workItemId, customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoanRecord> ownedLoan(long customerId, long loanId) {
        return jdbc.query("""
                select id,loan_no,balance,acceleration_declared,acceleration_date,outsourced,
                       agreement_loan,repayment_order,has_third_party_provider
                  from loan where id=? and customer_id=?
                """, rs -> rs.next() ? Optional.of(new LoanRecord(rs.getLong("id"), rs.getString("loan_no"),
                        rs.getBigDecimal("balance"), rs.getBoolean("acceleration_declared"),
                        rs.getObject("acceleration_date", java.time.LocalDate.class), rs.getBoolean("outsourced"),
                        rs.getBoolean("agreement_loan"), rs.getString("repayment_order"),
                        rs.getBoolean("has_third_party_provider"))) : Optional.empty(), loanId, customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanListRecord> ownedLoans(long customerId) {
        return jdbc.query("""
                select id,loan_no,loan_name,balance,overdue_days,acceleration_declared,
                       acceleration_date,repayment_order
                  from loan
                 where customer_id=?
                 order by id asc
                """, (rs, row) -> new LoanListRecord(rs.getLong("id"), rs.getString("loan_no"),
                rs.getString("loan_name"), rs.getBigDecimal("balance"), rs.getInt("overdue_days"),
                rs.getBoolean("acceleration_declared"),
                rs.getObject("acceleration_date", java.time.LocalDate.class),
                rs.getString("repayment_order")), customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExtractionRecord> latestExtraction(long workItemId) {
        return jdbc.query("""
                select e.document_file_id,e.status,e.extracted
                  from required_document rd
                  join document_file df on df.required_document_id=rd.id
                  join document_extraction e on e.document_file_id=df.id
                 where rd.work_item_id=? and rd.document_type_code='B01_REPAYMENT_ORDER_APPLICATION'
                   and df.is_latest=true and df.deleted_at is null
                 order by e.id desc limit 1
                """, rs -> rs.next() ? Optional.of(new ExtractionRecord(rs.getLong(1), rs.getString(2),
                        rs.getString(3))) : Optional.empty(), workItemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExtractionRecord> extractionForDocument(long workItemId, long documentFileId) {
        return jdbc.query("""
                select e.document_file_id,e.status,e.extracted
                  from required_document rd
                  join document_file df on df.required_document_id=rd.id
                  join document_extraction e on e.document_file_id=df.id
                 where rd.work_item_id=? and rd.document_type_code='B01_REPAYMENT_ORDER_APPLICATION'
                   and df.id=? and df.is_latest=true and df.deleted_at is null
                 order by e.id desc limit 1
                """, rs -> rs.next() ? Optional.of(new ExtractionRecord(rs.getLong(1), rs.getString(2),
                        rs.getString(3))) : Optional.empty(), workItemId, documentFileId);
    }
}
