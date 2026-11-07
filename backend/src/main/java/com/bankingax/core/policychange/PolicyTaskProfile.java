package com.bankingax.core.policychange;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional per-task knowledge. A task listed in app.policy-change.registered-tasks works without one; a task adds a
 * profile bean only when it can narrow candidates better than "every open Case of this task". Assessments are
 * read-only and must not change a Case.
 */
public interface PolicyTaskProfile {
    String taskCode();

    /** Problem codes for this task's part of detail.taskExtensions; any problem quarantines the event. */
    default List<String> validateExtension(JsonNode extension) { return List.of(); }

    /** Reason codes per Case id. A Case missing from the result is not a candidate. */
    default Map<Long, List<String>> assess(PolicyChange change, List<OpenCase> openCases) {
        Map<Long, List<String>> candidates = new LinkedHashMap<>();
        openCases.forEach(openCase -> candidates.put(openCase.caseId(), List.of("TASK_CODE_MATCH")));
        return candidates;
    }

    record OpenCase(long caseId, int revision, String caseStatus, Instant submittedAt, String ruleVersion) { }
}
