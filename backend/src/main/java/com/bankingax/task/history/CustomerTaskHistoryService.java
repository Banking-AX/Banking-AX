package com.bankingax.task.history;

import com.bankingax.core.api.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only T2 task history assembled from the existing Workflow/WorkItem/Case/result tables. */
@Service
public class CustomerTaskHistoryService {
    private static final String HISTORY_SQL = """
            select wi.task_code,
                   wi.id as work_item_id,
                   wi.status as work_item_status,
                   wf.status as workflow_status,
                   sc.id as case_id,
                   sc.case_status,
                   case
                     when wi.task_code = 'TRANSACTION_SEARCH'
                       and wi.status = 'DONE'
                       and exists (select 1 from task_result tr
                                   where tr.work_item_id = wi.id and tr.result_type = 'TRANSACTION_SEARCH') then true
                     when wi.task_code = 'HOUSING_SAVINGS_CLOSE'
                       and wi.status = 'DONE'
                       and exists (select 1 from task_result tr
                                   where tr.work_item_id = wi.id and tr.result_type = 'HOUSING_SAVINGS_CLOSE') then true
                     when wi.task_code = 'INHERITANCE_DEPOSIT' and sc.id is not null then true
                     else false
                   end as result_available
              from workflow wf
              join work_item wi on wi.workflow_id = wf.id
              left join service_case sc on sc.work_item_id = wi.id
                   and sc.customer_id = wf.customer_id
                   and sc.case_type = 'INHERITANCE_DEPOSIT'
             where wf.customer_id = :customerId
               and wi.task_code in ('TRANSACTION_SEARCH', 'HOUSING_SAVINGS_CLOSE', 'INHERITANCE_DEPOSIT')
             order by wi.last_activity_at desc, wi.id desc
             limit 100
            """;

    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider;

    public CustomerTaskHistoryService(ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    @Transactional(readOnly = true)
    public List<Item> listForCustomer(long customerId) {
        NamedParameterJdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new DomainException("TASK_HISTORY_UNAVAILABLE",
                    "Customer task history requires the persistent JDBC profile.");
        }
        return jdbc.query(HISTORY_SQL, new MapSqlParameterSource("customerId", customerId),
                (rs, row) -> map(rs));
    }

    private static Item map(ResultSet rs) throws SQLException {
        long caseIdValue = rs.getLong("case_id");
        Long caseId = rs.wasNull() ? null : caseIdValue;
        return new Item(rs.getString("task_code"), rs.getLong("work_item_id"),
                rs.getString("work_item_status"), rs.getString("workflow_status"), caseId,
                rs.getString("case_status"), rs.getBoolean("result_available"));
    }

    public record Item(String taskCode, long workItemId, String workItemStatus, String workflowStatus,
                       Long caseId, String caseStatus, boolean resultAvailable) { }
}
