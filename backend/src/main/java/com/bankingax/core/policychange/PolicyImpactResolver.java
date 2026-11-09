package com.bankingax.core.policychange;

import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Finds open Cases a change may concern. Read-only: being a candidate changes nothing about a Case. */
@Component
@ConditionalOnDataSource
public class PolicyImpactResolver {
    private static final String OPEN_CASES = """
            select sc.id,sc.revision,sc.case_status,sc.submitted_at,
                   (select min(rd.rule_version) from required_document rd where rd.work_item_id=sc.work_item_id)
              from service_case sc join work_item wi on wi.id=sc.work_item_id
             where wi.task_code=? and sc.case_status in ('SUBMITTED','IN_REVIEW')
             order by sc.submitted_at,sc.id limit 200
            """;

    private final JdbcTemplate jdbc;
    private final PolicyTaskRegistry tasks;

    public PolicyImpactResolver(JdbcTemplate jdbc, PolicyTaskRegistry tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    List<TaskImpact> resolve(PolicyChangeStore.Received received, List<PolicyChangeStore.Received> history) {
        List<TaskImpact> impacts = new ArrayList<>();
        for (String taskCode : received.mappedTaskCodes()) {
            List<PolicyTaskProfile.OpenCase> open = openCases(taskCode);
            Map<Long, List<String>> assessed = tasks.profile(taskCode).assess(received.change(), open);
            List<CaseImpact> candidates = open.stream().filter(row -> assessed.containsKey(row.caseId()))
                    .map(row -> candidate(row, assessed.get(row.caseId()), received.change(), history)).toList();
            impacts.add(new TaskImpact(taskCode, open.size(), candidates));
        }
        return impacts;
    }

    /** Why this change concerns one open Case of the task. Empty for a closed Case, whose history stays as it was. */
    List<String> reasonsFor(PolicyChange change, String taskCode, long caseId) {
        List<PolicyTaskProfile.OpenCase> open = openCases(taskCode).stream().filter(row -> row.caseId() == caseId).toList();
        return open.isEmpty() ? List.of() : tasks.profile(taskCode).assess(change, open).getOrDefault(caseId, List.of());
    }

    private List<PolicyTaskProfile.OpenCase> openCases(String taskCode) {
        return jdbc.query(OPEN_CASES, (rs, row) -> new PolicyTaskProfile.OpenCase(rs.getLong(1), rs.getInt(2), rs.getString(3),
                instant(rs.getObject(4, LocalDateTime.class)), rs.getString(5)), taskCode);
    }

    private static CaseImpact candidate(PolicyTaskProfile.OpenCase row, List<String> reasons, PolicyChange change,
                                        List<PolicyChangeStore.Received> history) {
        boolean beforeEffective = row.submittedAt() != null && row.submittedAt().isBefore(change.effectiveAt());
        return new CaseImpact(row.caseId(), row.revision(), row.caseStatus(), row.submittedAt(), row.ruleVersion(),
                versionAt(row.submittedAt(), history), beforeEffective, reasons);
    }

    /** Newest received version already published when the Case was submitted; null when the register had none. */
    private static String versionAt(Instant submittedAt, List<PolicyChangeStore.Received> history) {
        if (submittedAt == null) return null;
        return history.stream().filter(row -> PolicyChangeIngestService.ACCEPTED.equals(row.intakeStatus()))
                .filter(row -> !row.change().publishedAt().isAfter(submittedAt))
                .max(Comparator.comparingLong(row -> row.change().versionOrder()))
                .map(row -> row.change().sourceVersion()).orElse(null);
    }

    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }

    public record TaskImpact(String taskCode, int openCaseCount, List<CaseImpact> candidates) { }

    public record CaseImpact(long caseId, int revision, String caseStatus, Instant submittedAt, String ruleVersion,
                             String policyVersionAtSubmission, boolean submittedBeforeEffective, List<String> reasonCodes) { }
}
