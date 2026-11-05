package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.notification.NotificationWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-01-07~10: re-validates the application and applies the Mock repayment order change. The loan change, task
 * result, completion, audit event and web notification commit together, so a failed run leaves no notification.
 */
@Service
public class B01ExecutionService {
    static final String NOTIFICATION_TYPE = "B01_RESULT";
    private static final String TASK_CODE = "REPAYMENT_ORDER_CHANGE";
    private static final String RULE_VERSION = "1.7.1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final B01ProgressService progress;
    private final B01EligibilityService eligibility;
    private final NotificationWriter notifications;

    B01ExecutionService(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json, B01ProgressService progress,
                        B01EligibilityService eligibility, ObjectProvider<NotificationWriter> notifications) {
        this.jdbc = jdbc.getIfAvailable();
        this.json = json;
        this.progress = progress;
        this.eligibility = eligibility;
        this.notifications = notifications.getIfAvailable();
    }

    @Transactional
    public ExecutionResult execute(long customerId, long workItemId, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 64 characters is required.");
        if (jdbc == null || notifications == null)
            throw new DomainException("B01_PERSISTENCE_UNAVAILABLE", "B-01 persistence is not configured.", true);

        LockedWork work = lockWork(customerId, workItemId);
        if ("DONE".equals(work.status())) return replay(workItemId);
        if (!"ACTIVE".equals(work.status())) throw unavailable();

        JsonNode draft = read(work.draft());
        long loanId = draft.path("selectedLoanId").asLong();
        long documentFileId = draft.path("documentFileId").asLong();
        String requested = draft.path("requestedRepaymentOrder").asText(null);
        if (loanId <= 0 || documentFileId <= 0 || requested == null) throw notReady();

        // Both locks come first: every value judged below is read after a competing change has finished.
        LockedLoan loan = lockLoan(customerId, loanId);
        if (!progress.context(customerId, workItemId).readyToExecute()) throw notReady();
        var validated = eligibility.validate(customerId, workItemId, loanId, requested, documentFileId);
        if (!validated.loanRuleEligible() || !validated.ocr().matched() || !validated.image().matched()
                || !Objects.equals(validated.ocr().documentFileId(), documentFileId))
            throw new DomainException("B01_REVALIDATION_FAILED", "The loan or application no longer passes validation.");

        Instant processedAt = Instant.now();
        Instant consentAt = consentAt(draft.path("consentRecordId").asLong(), workItemId);
        jdbc.update("update loan set repayment_order=?,version=version+1 where id=? and customer_id=?",
                requested, loanId, customerId);
        long taskResultId = storeResult(workItemId, loanId, loan.order(), requested, validated.ruleCode().name(),
                detail(requestId, processedAt, consentAt, draft));
        complete(workItemId, work.workflowId());
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,actor_type,actor_id,request_id,payload) "
                + "values('WORK_ITEM',?,'B01_EXECUTED','CUSTOMER',?,?,cast(? as json))", Long.toString(workItemId),
                customerId, requestId + ":executed", write(Map.of("loanId", loanId, "taskResultId", taskResultId)));
        notifications.store(customerId, null, workItemId, taskResultId, NOTIFICATION_TYPE, "채무변제 충당순서 변경 완료",
                "대출 %s의 충당순서를 %s에서 %s(으)로 변경했어요. 시연용 모의 처리 결과입니다."
                        .formatted(mask(loan.loanNo()), label(loan.order()), label(requested)),
                "B01_RESULT:" + workItemId);
        return new ExecutionResult("DONE", processedAt, consentAt);
    }

    private LockedWork lockWork(long customerId, long workItemId) {
        List<LockedWork> rows = jdbc.query("""
                select wi.status,wi.workflow_id,wi.draft_data
                  from work_item wi join workflow wf on wf.id=wi.workflow_id
                  join `user` u on u.id=wf.customer_id
                 where wi.id=? and wf.customer_id=? and wi.task_code=? and u.is_active=true for update
                """, (rs, row) -> new LockedWork(rs.getString(1), rs.getLong(2), rs.getString(3)),
                workItemId, customerId, TASK_CODE);
        if (rows.isEmpty()) throw unavailable();
        return rows.get(0);
    }

    private LockedLoan lockLoan(long customerId, long loanId) {
        List<LockedLoan> rows = jdbc.query("select repayment_order,loan_no from loan where id=? and customer_id=? for update",
                (rs, row) -> new LockedLoan(rs.getString(1), rs.getString(2)), loanId, customerId);
        if (rows.isEmpty()) throw new DomainException("LOAN_NOT_AVAILABLE", "The selected loan is not available.");
        return rows.get(0);
    }

    /** A completed WorkItem answers from its stored result: no second change, event or notification. */
    private ExecutionResult replay(long workItemId) {
        JsonNode detail = read(jdbc.queryForObject("select detail from task_result where work_item_id=?",
                String.class, workItemId));
        return new ExecutionResult("DONE", Instant.parse(detail.path("processedAt").asText()),
                detail.hasNonNull("consentAt") ? Instant.parse(detail.path("consentAt").asText()) : null);
    }

    private Instant consentAt(long consentRecordId, long workItemId) {
        List<LocalDateTime> rows = jdbc.query("select agreed_at from consent_record where id=? and work_item_id=?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), consentRecordId, workItemId);
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toInstant(ZoneOffset.UTC);
    }

    private long storeResult(long workItemId, long loanId, String before, String after, String decision, String detail) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into task_result(work_item_id,result_type,decision_code,target_loan_id,rule_version,
                                            before_value,after_value,detail)
                    values(?,?,?,?,?,cast(? as json),cast(? as json),cast(? as json))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, workItemId);
            statement.setString(2, TASK_CODE);
            statement.setString(3, decision);
            statement.setLong(4, loanId);
            statement.setString(5, RULE_VERSION);
            statement.setString(6, write(Map.of("loanId", loanId, "repaymentOrder", before)));
            statement.setString(7, write(Map.of("loanId", loanId, "repaymentOrder", after)));
            statement.setString(8, detail);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private String detail(String requestId, Instant processedAt, Instant consentAt, JsonNode draft) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("processedAt", processedAt.toString());
        detail.put("consentAt", consentAt == null ? null : consentAt.toString());
        detail.put("consentRecordId", draft.path("consentRecordId").asLong());
        detail.put("documentFileId", draft.path("documentFileId").asLong());
        detail.put("applicationFingerprint", draft.path("applicationFingerprint").asText(null));
        return write(detail);
    }

    private void complete(long workItemId, long workflowId) {
        jdbc.update("update work_item set status='DONE',last_activity_at=UTC_TIMESTAMP(6),version=version+1 "
                + "where id=? and status='ACTIVE'", workItemId);
        jdbc.update("""
                update workflow set status=case when exists(select 1 from work_item
                         where workflow_id=? and status in ('PLANNED','ACTIVE','ON_HOLD'))
                       then 'WAITING_CONTINUE' else 'COMPLETED' end,
                       updated_at=UTC_TIMESTAMP(6),version=version+1 where id=?
                """, workflowId, workflowId);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value == null ? "{}" : value); }
        catch (Exception exception) { throw new DomainException("WORK_ITEM_DRAFT_INVALID", "Stored B-01 state is invalid.", true); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new DomainException("B01_STATE_PERSISTENCE_FAILED", "B-01 state could not be stored.", true); }
    }

    private static String label(String order) { return "PRINCIPAL_FIRST".equals(order) ? "원금 우선" : "이자 우선"; }

    private static String mask(String loanNo) {
        return loanNo == null || loanNo.length() < 4 ? "****" : "****" + loanNo.substring(loanNo.length() - 4);
    }

    private static DomainException notReady() {
        return new DomainException("B01_NOT_READY_TO_EXECUTE", "Consent, signature and SMS verification must be completed first.");
    }

    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
    }

    public record ExecutionResult(String status, Instant processedAt, Instant consentAt) { }
    private record LockedWork(String status, long workflowId, String draft) { }
    private record LockedLoan(String order, String loanNo) { }
}
